package zio.telemetry.opentelemetry.context

import io.opentelemetry.context.propagation.TextMapSetter
import zio.telemetry.opentelemetry.internal.ContextCarrier

import scala.collection.mutable

/**
 * The wrapper for the context data to propagate to an external process.
 *
 * @tparam T
 *   must be mutable
 */
trait OutgoingContextCarrier[T] extends ContextCarrier[T] with TextMapSetter[T]

object OutgoingContextCarrier {

  /**
   * Default implementation of the [[OutgoingContextCarrier]] where the type of the [[ContextCarrier.kernel]] is a
   * mutable `Map[String, String]``.
   *
   * @param initial
   *   initial kernel
   * @return
   */
  def default(
    initial: mutable.Map[String, String] = mutable.Map.empty
  ): OutgoingContextCarrier[mutable.Map[String, String]] =
    new OutgoingContextCarrier[mutable.Map[String, String]] {

      override val kernel: mutable.Map[String, String] = initial

      override def set(carrier: mutable.Map[String, String], key: String, value: String): Unit =
        carrier.update(key, value)

    }

}
