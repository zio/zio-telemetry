package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

/**
 * A Counter instrument that records values of type `A`
 *
 * @tparam A
 *   according to the specification, it can be either [[scala.Long]] or [[scala.Double]] type
 */
trait Counter[-A] {

  /**
   * Records a value.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param value
   *   increment amount. MUST be non-negative
   * @param attributes
   *   set of attributes to associate with the value
   */
  def add(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]

  /**
   * Increments a counter by one.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param attributes
   *   set of attributes to associate with the value
   */
  def inc(attributes: Attributes = Attributes.empty): UIO[Unit]
}

object Counter {

  private[metrics] def long(counter: LongCounter, ctxStorage: ContextStorage): Counter[Long] =
    new Counter[Long] {

      override def add(value: Long, attributes: Attributes = Attributes.empty): UIO[Unit] =
        ctxStorage.get.map(counter.add(value, attributes, _))

      override def inc(attributes: Attributes = Attributes.empty): UIO[Unit] =
        add(1L, attributes)

    }

}
