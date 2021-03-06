package zio.kafka.client

import java.util.UUID

import net.manub.embeddedkafka.{ EmbeddedK, EmbeddedKafka, EmbeddedKafkaConfig }
import org.apache.kafka.clients.consumer.ConsumerConfig
import zio.{ Chunk, Managed, RIO, Task, UIO, ZIO, ZManaged }
import org.apache.kafka.clients.producer.ProducerRecord
import zio.blocking.Blocking
import zio.clock.Clock
import zio.kafka.client.serde.Serde
import zio.duration._
import zio.kafka.client.Consumer.OffsetRetrieval
import zio.kafka.client.Kafka.{ KafkaClockBlocking, KafkaTestEnvironment }
import zio.kafka.client.diagnostics.Diagnostics
import zio.test.environment.{ Live, TestEnvironment }

trait Kafka {
  def kafka: Kafka.Service
}

object Kafka {
  trait Service {
    def bootstrapServers: List[String]
    def stop(): UIO[Unit]
  }

  case class EmbeddedKafkaService(embeddedK: EmbeddedK) extends Kafka.Service {
    override def bootstrapServers: List[String] = List(s"localhost:${embeddedK.config.kafkaPort}")
    override def stop(): UIO[Unit]              = ZIO.effectTotal(embeddedK.stop(true))
  }

  case object DefaultLocal extends Kafka.Service {
    override def bootstrapServers: List[String] = List(s"localhost:9092")

    override def stop(): UIO[Unit] = UIO.unit
  }

  implicit val embeddedKafkaConfig = EmbeddedKafkaConfig(
    customBrokerProperties = Map("group.min.session.timeout.ms" -> "500", "group.initial.rebalance.delay.ms" -> "0")
  )

  val makeEmbedded: Managed[Nothing, Kafka] =
    ZManaged.make(ZIO.effectTotal(new Kafka {
      override val kafka: Service = EmbeddedKafkaService(EmbeddedKafka.start())
    }))(_.kafka.stop())

  val makeLocal: Managed[Nothing, Kafka] =
    ZManaged.make(ZIO.effectTotal(new Kafka {
      override val kafka: Service = DefaultLocal
    }))(_.kafka.stop())

  type KafkaTestEnvironment = Kafka with TestEnvironment

  type KafkaClockBlocking = Kafka with Clock with Blocking

  def liveClockBlocking: ZIO[KafkaTestEnvironment, Nothing, Kafka with Clock with Blocking] =
    for {
      clck    <- Live.live(ZIO.environment[Clock])
      blcking <- ZIO.environment[Blocking]
      kfka    <- ZIO.environment[Kafka]
    } yield new Kafka with Clock with Blocking {
      override val kafka: Service = kfka.kafka

      override val clock: Clock.Service[Any]       = clck.clock
      override val blocking: Blocking.Service[Any] = blcking.blocking
    }

}

object KafkaTestUtils {

  def kafkaEnvironment(kafkaE: Managed[Nothing, Kafka]): Managed[Nothing, KafkaTestEnvironment] =
    for {
      testEnvironment <- TestEnvironment.Value
      kafkaS          <- kafkaE
    } yield new TestEnvironment(
      testEnvironment.blocking,
      testEnvironment.clock,
      testEnvironment.console,
      testEnvironment.live,
      testEnvironment.random,
      testEnvironment.sized,
      testEnvironment.system
    ) with Kafka {
      val kafka = kafkaS.kafka
    }

  val embeddedKafkaEnvironment: Managed[Nothing, KafkaTestEnvironment] =
    kafkaEnvironment(Kafka.makeEmbedded)

  val localKafkaEnvironment: Managed[Nothing, KafkaTestEnvironment] =
    kafkaEnvironment(Kafka.makeLocal)

  def producerSettings =
    ZIO.access[Kafka](_.kafka.bootstrapServers).map(ProducerSettings(_))

  def withProducer[A, K, V](
    r: Producer[Any, K, V] => RIO[Any with Clock with Kafka with Blocking, A],
    kSerde: Serde[Any, K],
    vSerde: Serde[Any, V]
  ): RIO[KafkaTestEnvironment, A] =
    for {
      settings <- producerSettings
      producer = Producer.make(settings, kSerde, vSerde)
      lcb      <- Kafka.liveClockBlocking
      produced <- producer.use { p =>
                   r(p).provide(lcb)
                 }
    } yield produced

  def withProducerStrings[A](r: Producer[Any, String, String] => RIO[Any with Clock with Kafka with Blocking, A]) =
    withProducer(r, Serde.string, Serde.string)

  def produceOne(t: String, k: String, m: String) =
    withProducerStrings { p =>
      p.produce(new ProducerRecord(t, k, m))
    }.flatten

  def produceMany(t: String, kvs: Iterable[(String, String)]) =
    withProducerStrings { p =>
      val records = kvs.map {
        case (k, v) => new ProducerRecord[String, String](t, k, v)
      }
      val chunk = Chunk.fromIterable(records)
      p.produceChunk(chunk)
    }.flatten

  def produceMany(topic: String, partition: Int, kvs: Iterable[(String, String)]) =
    withProducerStrings { p =>
      val records = kvs.map {
        case (k, v) => new ProducerRecord[String, String](topic, partition, null, k, v)
      }
      val chunk = Chunk.fromIterable(records)
      p.produceChunk(chunk)
    }.flatten

  def consumerSettings(groupId: String, clientId: String, offsetRetrieval: OffsetRetrieval = OffsetRetrieval.Auto()) =
    ZIO
      .access[Kafka](_.kafka.bootstrapServers)
      .map(
        ConsumerSettings(_)
          .withGroupId(groupId)
          .withClientId(clientId)
          .withCloseTimeout(5.seconds)
          .withProperties(
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG     -> "earliest",
            ConsumerConfig.METADATA_MAX_AGE_CONFIG      -> "100",
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG    -> "1000",
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG -> "250",
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG      -> "10"
          )
          .withPerPartitionChunkPrefetch(16)
          .withOffsetRetrieval(offsetRetrieval)
      )

  def consumeWithStrings(groupId: String, clientId: String, subscription: Subscription)(
    r: (String, String) => ZIO[Any with Kafka with Clock with Blocking, Nothing, Unit]
  ): RIO[KafkaTestEnvironment, Unit] =
    for {
      lcb <- Kafka.liveClockBlocking
      inner <- (for {
                settings <- consumerSettings(groupId, clientId)
                consumed <- Consumer.consumeWith(settings, subscription, Serde.string, Serde.string)(r)
              } yield consumed)
                .provide(lcb)
    } yield inner

  def withConsumer[A, R <: KafkaClockBlocking](
    groupId: String,
    clientId: String,
    diagnostics: Diagnostics = Diagnostics.NoOp,
    offsetRetrieval: OffsetRetrieval = OffsetRetrieval.Auto()
  )(
    r: Consumer => RIO[R, A]
  ): RIO[KafkaTestEnvironment, A] =
    for {
      lcb <- Kafka.liveClockBlocking
      inner <- (for {
                settings <- consumerSettings(groupId, clientId, offsetRetrieval)
                consumer = Consumer.make(settings, diagnostics)
                consumed <- consumer.use(r)
              } yield consumed)
                .provide(lcb.asInstanceOf[R]) // Because an LCB is also an R but somehow scala can't infer that
    } yield inner

  def adminSettings =
    ZIO.access[Kafka](_.kafka.bootstrapServers).map(AdminClientSettings(_))

  def withAdmin[T](f: AdminClient => RIO[Any with Clock with Kafka with Blocking, T]) =
    for {
      settings <- adminSettings
      lcb      <- Kafka.liveClockBlocking
      fRes <- AdminClient
               .make(settings)
               .use { client =>
                 f(client)
               }
               .provide(lcb)
    } yield fRes

  // temporary workaround for zio issue #2166 - broken infinity
  val veryLongTime = Duration.fromNanos(Long.MaxValue)

  def randomThing(prefix: String) =
    for {
      l <- Task(UUID.randomUUID())
    } yield s"$prefix-$l"

  def randomTopic = randomThing("topic")

  def randomGroup = randomThing("group")

}
