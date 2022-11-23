package zio.telemetry.opentelemetry.baggage

import io.opentelemetry.api.baggage.{ Baggage => Baggaje, BaggageBuilder, BaggageEntryMetadata }
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

import scala.jdk.CollectionConverters._

trait Baggage { self =>

  /**
   * Gets the baggage from current context.
   *
   * @param trace
   * @return
   */
  def getCurrentBaggage(implicit trace: Trace): UIO[Baggaje]

  /**
   * Gets the value by a given name.
   *
   * @param name
   * @param trace
   * @return
   *   value
   */
  def get(name: String)(implicit trace: Trace): UIO[Option[String]]

  /**
   * Gets all values.
   *
   * @param trace
   * @return
   *   all values
   */
  def getAll(implicit trace: Trace): UIO[Map[String, String]]

  /**
   * Gets all values accompanied by metadata.
   *
   * @param trace
   * @return
   */
  def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]]

  /**
   * Sets the new value for a given name.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def set(name: String, value: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the new value and metadata for a given name.
   *
   * @param name
   * @param value
   * @param metadata
   *   opaque string
   * @param trace
   * @return
   */
  def setWithMetadata(name: String, value: String, metadata: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Removes the name/value by a given name.
   *
   * @param name
   * @param trace
   * @return
   */
  def remove(name: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Injects the baggage data from the current context into carrier `C`
   *
   * @param propagator
   *   implementation of [[TextMapPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param setter
   *   implementation of [[TextMapSetter]] which extracts the baggage data from the current context into carrier `C`
   * @param Trace
   * @tparam C
   *   carrier
   * @return
   */
  def inject[C](
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapSetter[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Extracts the baggage data from carrier `C` into the current context
   *
   * @param propagator
   *   implementation of [[TextMapPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param getter
   *   implementation of [[TextMapGetter]] which injects the baggage data from carrier `C` into the current context
   * @param trace
   * @tparam C
   *   carrier
   * @return
   */
  def extract[C](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C]
  )(implicit trace: Trace): UIO[Unit]

}

object Baggage {

  def live: URLayer[ContextStorage, Baggage] =
    ZLayer.scoped {
      for {
        contextStorage <- ZIO.service[ContextStorage]
        baggage        <- scoped(contextStorage)
      } yield baggage
    }

  def scoped(currentContext: ContextStorage): URIO[Scope, Baggage] =
    ZIO.succeed {
      new Baggage { self =>
        override def getCurrentBaggage(implicit trace: Trace): UIO[Baggaje] =
          currentContext.get.map(Baggaje.fromContext)

        override def get(name: String)(implicit trace: Trace): UIO[Option[String]] =
          getCurrentBaggage.map(baggage => Option(baggage.getEntryValue(name)))

        override def getAll(implicit trace: Trace): UIO[Map[String, String]] =
          getCurrentBaggage.map(_.asMap().asScala.toMap.mapValues(_.getValue))

        override def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]] =
          getCurrentBaggage.map(
            _.asMap().asScala.toMap.mapValues(e => e.getValue -> e.getMetadata.getValue)
          )

        override def set(name: String, value: String)(implicit trace: Trace): UIO[Unit] =
          modifyBuilder(_.put(name, value)).unit

        override def setWithMetadata(name: String, value: String, metadata: String)(implicit trace: Trace): UIO[Unit] =
          modifyBuilder(_.put(name, value, BaggageEntryMetadata.create(metadata))).unit

        override def remove(name: String)(implicit trace: Trace): UIO[Unit] =
          modifyBuilder(_.remove(name)).unit

        override def inject[C](
          propagator: TextMapPropagator,
          carrier: C,
          setter: TextMapSetter[C]
        )(implicit trace: Trace): UIO[Unit] =
          for {
            ctx <- getCurrentContext
            _   <- ZIO.succeed(propagator.inject(ctx, carrier, setter))
          } yield ()

        override def extract[C](
          propagator: TextMapPropagator,
          carrier: C,
          getter: TextMapGetter[C]
        )(implicit trace: Trace): UIO[Unit] =
          modify(ctx => propagator.extract(ctx, carrier, getter)).unit

        private def getCurrentContext(implicit trace: Trace): UIO[Context] =
          currentContext.get

        private def modify(body: Context => Context): UIO[Context] =
          currentContext.updateAndGet(body)

        private def modifyBuilder(body: BaggageBuilder => BaggageBuilder)(implicit trace: Trace): UIO[Context] =
          modify { ctx =>
            body(Baggaje.fromContext(ctx).toBuilder)
              .build()
              .storeInContext(ctx)
          }

      }
    }

}
