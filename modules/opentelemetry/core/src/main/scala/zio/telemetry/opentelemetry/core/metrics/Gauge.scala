package zio.telemetry.opentelemetry.core.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleGauge
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage
import zio.telemetry.opentelemetry.core.metrics.internal.{Instrument, logAnnotatedAttributes}

/**
 * A Gauge instrument that records values of type `A`
 *
 * @tparam A
 *   according to the specification, it can be either [[scala.Long]] or [[scala.Double]] type
 */
trait Gauge[-A] extends Instrument[A] {

  /**
   * Sets a value.
   *
   * It uses the context taken from the ContextStorage to associate with this measurement.
   *
   * @param value
   *   set the gauge value
   * @param attributes
   *   set of attributes to associate with the value
   */
  def set(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

}

object Gauge {

  private[metrics] def double(
    gauge: DoubleGauge,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Gauge[Double] =
    new Gauge[Double] {

      override def record0(value: Double, attributes: Attributes, context: Context): Unit =
        gauge.set(value, attributes, context)

      override def set(value: Double, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

    }

}
