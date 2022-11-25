package zio.telemetry.opentelemetry.context

import io.opentelemetry.context.propagation.TextMapSetter
import zio.telemetry.opentelemetry.internal.ContextCarrier

import scala.collection.mutable

trait OutgoingContextCarrier[T] extends ContextCarrier[T] with TextMapSetter[T]

object OutgoingContextCarrier {

  def default(
    initial: mutable.Map[String, String] = mutable.Map.empty
  ): OutgoingContextCarrier[mutable.Map[String, String]] =
    new OutgoingContextCarrier[mutable.Map[String, String]] {

      override val kernel: mutable.Map[String, String] = initial

      override def set(carrier: mutable.Map[String, String], key: String, value: String): Unit =
        carrier.update(key, value)

    }

}
