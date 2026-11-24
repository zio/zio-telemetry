package zio.telemetry.opentelemetry.baggage

import io.opentelemetry.api.baggage.{Baggage => Baggaje, BaggageBuilder, BaggageEntryMetadata}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}

import scala.jdk.CollectionConverters._

/**
 * Reads and writes baggage entries. A baggage entry has a name, a value and optional metadata. The entries are stored
 * in the `Context` held by the [[zio.telemetry.opentelemetry.context.ContextStorage]].
 *
 * Use [[Baggage.live]] or [[zio.telemetry.opentelemetry.OpenTelemetry.baggage]] to create an instance.
 */
trait Baggage { self =>

  /**
   * Extracts the baggage from `carrier` with `propagator` and stores it in the current `Context`.
   *
   * @param propagator
   *   implementation of [[zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator]]
   * @param carrier
   *   mutable data from which the baggage is extracted
   * @tparam C
   *   carrier
   */
  def extract[C](
    propagator: BaggagePropagator,
    carrier: IncomingContextCarrier[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Gets the value of the baggage entry `name`, or `None` when there is no such entry.
   *
   * @param name
   *   name of the entry
   */
  def get(name: String)(implicit trace: Trace): UIO[Option[String]]

  /**
   * Gets the value of every baggage entry, keyed by name.
   */
  def getAll(implicit trace: Trace): UIO[Map[String, String]]

  /**
   * Gets every baggage entry as a pair of its value and the value of its metadata, keyed by name.
   */
  def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]]

  /**
   * Gets the OpenTelemetry `Baggage` of the current `Context`.
   */
  def getCurrentBaggageUnsafe(implicit trace: Trace): UIO[Baggaje]

  /**
   * Writes the baggage of the current `Context` into `carrier` with `propagator`.
   *
   * @param propagator
   *   implementation of [[zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator]]
   * @param carrier
   *   mutable data into which the baggage is injected
   * @tparam C
   *   carrier
   */
  def inject[C](
    propagator: BaggagePropagator,
    carrier: OutgoingContextCarrier[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Removes the baggage entry `name` from the current `Context`.
   *
   * @param name
   *   name of the entry
   */
  def remove(name: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the value of the baggage entry `name`, replacing any previous value. The entry is stored without metadata.
   *
   * @param name
   *   name of the entry
   * @param value
   *   value of the entry
   */
  def set(name: String, value: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the value and the metadata of the baggage entry `name`, replacing any previous entry.
   *
   * @param name
   *   name of the entry
   * @param value
   *   value of the entry
   * @param metadata
   *   opaque string stored with the entry
   */
  def setWithMetadata(name: String, value: String, metadata: String)(implicit trace: Trace): UIO[Unit]

}

/**
 * Constructors for [[Baggage]].
 */
object Baggage {

  /**
   * Creates a [[Baggage]] that stores its entries in the `Context` held by the
   * [[zio.telemetry.opentelemetry.context.ContextStorage]].
   *
   * When `logAnnotated` is `true`, each operation first copies the ZIO log annotations into the baggage, with the
   * metadata `zio log annotation`. Baggage entries that already exist take precedence over log annotations with the
   * same name.
   *
   * @param logAnnotated
   *   propagate ZIO log annotations as baggage entries when `true`
   */
  def live(logAnnotated: Boolean = false): URLayer[ContextStorage, Baggage] =
    ZLayer {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
      } yield new Baggage { self =>
        override def getCurrentBaggageUnsafe(implicit trace: Trace): UIO[Baggaje] =
          injectLogAnnotations *>
            getCurrentContext.map(Baggaje.fromContext)

        override def get(name: String)(implicit trace: Trace): UIO[Option[String]] =
          injectLogAnnotations *>
            getCurrentBaggageUnsafe.map(baggage => Option(baggage.getEntryValue(name)))

        override def getAll(implicit trace: Trace): UIO[Map[String, String]] =
          injectLogAnnotations *>
            getCurrentBaggageUnsafe.map(_.asMap().asScala.toMap.map { case (k, v) => k -> v.getValue })

        override def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]] =
          injectLogAnnotations *>
            getCurrentBaggageUnsafe.map(
              _.asMap().asScala.toMap.map { case (k, v) => (k, (v.getValue, v.getMetadata.getValue)) }
            )

        override def set(name: String, value: String)(implicit trace: Trace): UIO[Unit] =
          injectLogAnnotations *> modifyBuilder(_.put(name, value)).unit

        override def setWithMetadata(name: String, value: String, metadata: String)(implicit trace: Trace): UIO[Unit] =
          injectLogAnnotations *> modifyBuilder(_.put(name, value, BaggageEntryMetadata.create(metadata))).unit

        override def remove(name: String)(implicit trace: Trace): UIO[Unit] =
          injectLogAnnotations *> modifyBuilder(_.remove(name)).unit

        override def inject[C](
          propagator: BaggagePropagator,
          carrier: OutgoingContextCarrier[C]
        )(implicit trace: Trace): UIO[Unit] =
          for {
            _   <- injectLogAnnotations
            ctx <- getCurrentContext
            _   <- ZIO.succeed(propagator.instance.inject(ctx, carrier.kernel, carrier))
          } yield ()

        override def extract[C](
          propagator: BaggagePropagator,
          carrier: IncomingContextCarrier[C]
        )(implicit trace: Trace): UIO[Unit] =
          injectLogAnnotations *>
            ZIO.uninterruptible {
              modifyContext(ctx => propagator.instance.extract(ctx, carrier.kernel, carrier)).unit
            }

        private def getCurrentContext(implicit trace: Trace): UIO[Context] =
          ctxStorage.get

        private def modifyBuilder(body: BaggageBuilder => BaggageBuilder)(implicit trace: Trace): UIO[Context] =
          modifyContext { ctx =>
            body(Baggaje.fromContext(ctx).toBuilder)
              .build()
              .storeInContext(ctx)
          }

        private def modifyContext(body: Context => Context)(implicit trace: Trace): UIO[Context] =
          ctxStorage.updateAndGet(body)

        private def injectLogAnnotations(implicit trace: Trace): UIO[Unit] =
          ZIO
            .when(logAnnotated) {
              for {
                annotations            <- ZIO.logAnnotations
                annotationsWithMetadata = annotations.map { case (k, v) =>
                                            (k, (v, BaggageEntryMetadata.create("zio log annotation")))
                                          }
                current                <- getCurrentContext
                                            .map(Baggaje.fromContext)
                                            .map(_.asMap().asScala.toMap.map { case (k, v) => (k, (v.getValue, v.getMetadata)) })
                _                      <- modifyBuilder { builder =>
                                            (annotationsWithMetadata ++ current).foreach { case (k, (v, m)) => builder.put(k, v, m) }
                                            builder
                                          }
              } yield ()
            }
            .unit
      }
    }

}
