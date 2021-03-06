package zio.kafka.client

import org.apache.kafka.clients.consumer.{ OffsetAndMetadata, OffsetAndTimestamp }
import org.apache.kafka.common.{ PartitionInfo, TopicPartition }
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.kafka.client.Consumer.OffsetRetrieval
import zio.kafka.client.serde.Deserializer
import zio.kafka.client.diagnostics.Diagnostics
import zio.kafka.client.internal.{ ConsumerAccess, Runloop }
import zio.stream._

import scala.jdk.CollectionConverters._
import scala.collection.compat._

class Consumer private (
  private val consumer: ConsumerAccess,
  private val settings: ConsumerSettings,
  private val runloop: Runloop
) {
  self =>

  /**
   * Returns the topic-partitions that this consumer is currently assigned.
   *
   * This is subject to consumer rebalancing, unless using a manual subscription.
   */
  def assignment: BlockingTask[Set[TopicPartition]] =
    consumer.withConsumer(_.assignment().asScala.toSet)

  def beginningOffsets(
    partitions: Set[TopicPartition],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, Long]] =
    consumer.withConsumer(
      _.beginningOffsets(partitions.asJava, timeout.asJava).asScala.view.mapValues(_.longValue()).toMap
    )

  /**
   * Retrieve the last committed offset for the given topic-partitions
   */
  def committed(
    partitions: Set[TopicPartition],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, Option[OffsetAndMetadata]]] =
    consumer.withConsumer(
      _.committed(partitions.asJava, timeout.asJava).asScala.toMap.view.mapValues(Option.apply).toMap
    )

  def endOffsets(
    partitions: Set[TopicPartition],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, Long]] =
    consumer.withConsumer { eo =>
      val offs = eo.endOffsets(partitions.asJava, timeout.asJava)
      offs.asScala.view.mapValues(_.longValue()).toMap
    }

  /**
   * Stops consumption of data, drains buffered records, and ends the attached
   * streams while still serving commit requests.
   */
  def stopConsumption: UIO[Unit] =
    runloop.deps.gracefulShutdown

  def listTopics(timeout: Duration = Duration.Infinity): BlockingTask[Map[String, List[PartitionInfo]]] =
    consumer.withConsumer(_.listTopics(timeout.asJava).asScala.view.mapValues(_.asScala.toList).toMap)

  def offsetsForTimes(
    timestamps: Map[TopicPartition, Long],
    timeout: Duration = Duration.Infinity
  ): BlockingTask[Map[TopicPartition, OffsetAndTimestamp]] =
    consumer.withConsumer(
      _.offsetsForTimes(timestamps.view.mapValues(Long.box).toMap.asJava, timeout.asJava).asScala.toMap
    )

  /**
   * Create a stream with messages on the subscribed topic-partitions by topic-partition
   *
   * The top-level stream will emit new topic-partition streams for each topic-partition that is assigned
   * to this consumer. This is subject to consumer rebalancing, unless a manual subscription
   * was made. When rebalancing occurs, new topic-partition streams may be emitted and existing
   * streams may be completed.
   *
   * All streams can be completed by calling [[stopConsumption]].
   *
   * @param keyDeserializer Deserializer for the record keys
   * @param valueDeserializer Deserializer for the record values
   * @tparam R Environment required by the serializers
   * @tparam K Type of record keys
   * @tparam V Type of record values
   * @return
   */
  def partitionedStream[R, K, V](
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V]
  ): ZStream[
    Clock with Blocking,
    Throwable,
    (TopicPartition, ZStreamChunk[R, Throwable, CommittableRecord[K, V]])
  ] =
    ZStream
      .fromQueue(runloop.deps.partitions)
      .unTake
      .map {
        case (tp, partition) =>
          val partitionStream =
            if (settings.perPartitionChunkPrefetch <= 0) partition
            else ZStreamChunk(partition.chunks.buffer(settings.perPartitionChunkPrefetch))

          tp -> partitionStream.mapM(_.deserializeWith(keyDeserializer, valueDeserializer))
      }

  def partitionsFor(topic: String, timeout: Duration = Duration.Infinity): BlockingTask[List[PartitionInfo]] =
    consumer.withConsumer(_.partitionsFor(topic, timeout.asJava).asScala.toList)

  def position(partition: TopicPartition, timeout: Duration = Duration.Infinity): BlockingTask[Long] =
    consumer.withConsumer(_.position(partition, timeout.asJava))

  /**
   * Create a stream with all messages on the subscribed topic-partitions
   *
   * The stream will emit messages from all topic-partitions interleaved. Per-partition
   * record order is guaranteed, but the topic-partition interleaving is non-deterministic.
   *
   * The stream can be completed by calling [[stopConsumption]].
   *
   * @param keyDeserializer Deserializer for the record keys
   * @param valueDeserializer Deserializer for the record values
   * @tparam R Environment required by the serializers
   * @tparam K Type of record keys
   * @tparam V Type of record values
   * @return
   */
  def plainStream[R, K, V](
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V]
  ): ZStreamChunk[R with Clock with Blocking, Throwable, CommittableRecord[K, V]] =
    ZStreamChunk(
      partitionedStream[R, K, V](keyDeserializer, valueDeserializer)
        .flatMapPar(n = Int.MaxValue)(_._2.chunks)
    )

  @deprecated("Use OffsetRetrieval.Manual", since = "0.5.0")
  def seek(partition: TopicPartition, offset: Long): BlockingTask[Unit] =
    consumer.withConsumer(_.seek(partition, offset))

  @deprecated("Use OffsetRetrieval.Manual", since = "0.5.0")
  def seekToBeginning(partitions: Set[TopicPartition]): BlockingTask[Unit] =
    consumer.withConsumer(_.seekToBeginning(partitions.asJava))

  @deprecated("Use OffsetRetrieval.Manual", since = "0.5.0")
  def seekToEnd(partitions: Set[TopicPartition]): BlockingTask[Unit] =
    consumer.withConsumer(_.seekToEnd(partitions.asJava))

  def subscribe(subscription: Subscription): BlockingTask[Unit] =
    consumer.withConsumerM { c =>
      subscription match {
        case Subscription.Pattern(pattern) => ZIO(c.subscribe(pattern.pattern, runloop.deps.rebalanceListener))
        case Subscription.Topics(topics)   => ZIO(c.subscribe(topics.asJava, runloop.deps.rebalanceListener))

        // For manual subscriptions we have to do some manual work before starting the run loop
        case Subscription.Manual(topicPartitions) =>
          ZIO(c.assign(topicPartitions.asJava)) *>
            ZIO.foreach_(topicPartitions)(runloop.deps.newPartitionStream) *> {
            settings.offsetRetrieval match {
              case OffsetRetrieval.Manual(getOffsets) =>
                getOffsets(topicPartitions).flatMap { offsets =>
                  ZIO.traverse_(offsets) { case (tp, offset) => ZIO(c.seek(tp, offset)) }
                }
              case OffsetRetrieval.Auto(_) => ZIO.unit
            }
          }
      }
    }

  def subscribeAnd(subscription: Subscription): SubscribedConsumer =
    new SubscribedConsumer(subscribe(subscription).as(self))

  def subscription: BlockingTask[Set[String]] =
    consumer.withConsumer(_.subscription().asScala.toSet)

  def unsubscribe: BlockingTask[Unit] =
    consumer.withConsumer(_.unsubscribe())
}

object Consumer {
  val offsetBatches: ZSink[Any, Nothing, Nothing, Offset, OffsetBatch] =
    ZSink.foldLeft[Offset, OffsetBatch](OffsetBatch.empty)(_ merge _)

  def make(
    settings: ConsumerSettings,
    diagnostics: Diagnostics = Diagnostics.NoOp
  ): ZManaged[Clock with Blocking, Throwable, Consumer] =
    for {
      wrapper <- ConsumerAccess.make(settings)
      deps <- Runloop.Deps.make(
               wrapper,
               settings.pollInterval,
               settings.pollTimeout,
               diagnostics,
               settings.offsetRetrieval
             )
      runloop <- Runloop(deps)
    } yield new Consumer(wrapper, settings, runloop)

  /**
   * Execute an effect for each record and commit the offset after processing
   *
   * This method is the easiest way of processing messages on a Kafka topic.
   *
   * Messages on a single partition are processed sequentially, while the processing of
   * multiple partitions happens in parallel.
   *
   * Offsets are committed after execution of the effect. They are batched when a commit action is in progress
   * to avoid backpressuring the stream. When commits fail due to a org.apache.kafka.clients.consumer.RetriableCommitFailedException they are
   * retried according to commitRetryPolicy
   *
   * The effect should absorb any failures. Failures should be handled by retries or ignoring the
   * error, which will result in the Kafka message being skipped.
   *
   * Messages are processed with 'at least once' consistency: it is not guaranteed that every message
   * that is processed by the effect has a corresponding offset commit before stream termination.
   *
   * Usage example:
   *
   * {{{
   * val settings: ConsumerSettings = ???
   * val subscription = Subscription.Topics(Set("my-kafka-topic"))
   *
   * val consumerIO = Consumer.consumeWith(settings, subscription, Serdes.string, Serdes.string) { case (key, value) =>
   *   // Process the received record here
   *   putStrLn(s"Received record: \${key}: \${value}")
   * }
   * }}}
   *
   * @param settings Settings for creating a [[Consumer]]
   * @param subscription Topic subscription parameters
   * @param keyDeserializer Deserializer for the key of the messages
   * @param valueDeserializer Deserializer for the value of the messages
   * @param commitRetryPolicy Retry commits that failed due to a RetriableCommitFailedException according to this schedule
   * @param f Function that returns the effect to execute for each message. It is passed the key and value
   * @tparam R Environment for the consuming effect
   * @tparam R1 Environment for the deserializers
   * @tparam K Type of keys (an implicit `Deserializer` should be in scope)
   * @tparam V Type of values (an implicit `Deserializer` should be in scope)
   * @return Effect that completes with a unit value only when interrupted. May fail when the [[Consumer]] fails.
   */
  def consumeWith[R, R1, K, V](
    settings: ConsumerSettings,
    subscription: Subscription,
    keyDeserializer: Deserializer[R1, K],
    valueDeserializer: Deserializer[R1, V],
    commitRetryPolicy: Schedule[Clock, Any, Any] = Schedule.exponential(1.second) && Schedule.recurs(3)
  )(
    f: (K, V) => ZIO[R, Nothing, Unit]
  ): ZIO[R with R1 with Blocking with Clock, Throwable, Unit] =
    ZStream
      .managed(Consumer.make(settings))
      .flatMap { consumer =>
        ZStream
          .fromEffect(consumer.subscribe(subscription))
          .flatMap { _ =>
            consumer
              .partitionedStream[R1, K, V](keyDeserializer, valueDeserializer)
              .flatMapPar(Int.MaxValue, outputBuffer = settings.perPartitionChunkPrefetch) {
                case (_, partitionStream) =>
                  partitionStream.mapM {
                    case CommittableRecord(record, offset) =>
                      f(record.key(), record.value()).as(offset)
                  }.flattenChunks
              }
          }
      }
      .aggregateAsync(offsetBatches)
      .mapM(_.commitOrRetry(commitRetryPolicy))
      .runDrain

  sealed trait OffsetRetrieval

  object OffsetRetrieval {
    final case class Auto(reset: AutoOffsetStrategy = AutoOffsetStrategy.Latest)                extends OffsetRetrieval
    final case class Manual(getOffsets: Set[TopicPartition] => Task[Map[TopicPartition, Long]]) extends OffsetRetrieval
  }

  sealed trait AutoOffsetStrategy { self =>
    def toConfig = self match {
      case AutoOffsetStrategy.Earliest => "earliest"
      case AutoOffsetStrategy.Latest   => "latest"
      case AutoOffsetStrategy.None     => "none"
    }
  }

  object AutoOffsetStrategy {
    case object Earliest extends AutoOffsetStrategy
    case object Latest   extends AutoOffsetStrategy
    case object None     extends AutoOffsetStrategy
  }
}
