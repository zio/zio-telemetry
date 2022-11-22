package zio.telemetry.opentelemetry.baggage

import io.opentelemetry.api.baggage.{ Baggage => Baggaje, BaggageBuilder, BaggageEntryMetadata }
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.tracing.ContextStorage

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
  def get(name: String)(implicit trace: Trace): UIO[String]

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

}

object Baggage {

  def live: ULayer[Baggage] =
    ZLayer.scoped(
      FiberRef
        .make[Context](Context.root())
        .flatMap(ref => scoped(ContextStorage.fiberRef(ref)))
    )

  def scoped(currentContext: ContextStorage): URIO[Scope, Baggage] =
    ZIO.succeed {
      new Baggage { self =>
        override def getCurrentBaggage(implicit trace: Trace): UIO[Baggaje] =
          currentContext.get.map(Baggaje.fromContext)

        override def get(name: String)(implicit trace: Trace): UIO[String] =
          getCurrentBaggage.map(_.getEntryValue(name))

        override def getAll(implicit trace: Trace): UIO[Map[String, String]] =
          getCurrentBaggage.map(_.asMap().asScala.toMap.mapValues(_.getValue))

        override def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]] =
          getCurrentBaggage.map(
            _.asMap().asScala.toMap.mapValues(e => e.getValue -> e.getMetadata.getValue)
          )

        override def set(name: String, value: String)(implicit trace: Trace): UIO[Unit] =
          modify(_.put(name, value)).unit

        override def setWithMetadata(name: String, value: String, metadata: String)(implicit trace: Trace): UIO[Unit] =
          modify(_.put(name, value, BaggageEntryMetadata.create(metadata))).unit

        override def remove(name: String)(implicit trace: Trace): UIO[Unit] =
          modify(_.remove(name)).unit

        private def modify(body: BaggageBuilder => BaggageBuilder)(implicit trace: Trace): UIO[Context] =
          currentContext.updateAndGet { context =>
            body(Baggaje.fromContext(context).toBuilder)
              .build()
              .storeInContext(context)
          }

      }
    }

}
