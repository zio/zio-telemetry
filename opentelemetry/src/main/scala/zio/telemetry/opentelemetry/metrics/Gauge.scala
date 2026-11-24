package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.{DoubleGauge, LongGauge}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, logAnnotatedAttributes}

/**
 * A Gauge instrument that records the current value of type `A`.
 *
 * @tparam A
 *   the type of the recorded value, either [[scala.Long]] or [[scala.Double]]
 */
trait Gauge[-A] extends Instrument[A] {

  /**
   * Records the current value.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param value
   *   the current value to record
   * @param attributes
   *   set of attributes to associate with the value
   */
  def set(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

}

/**
 * Constructors for [[Gauge]].
 */
object Gauge {

  private[metrics] def double(gauge: DoubleGauge, ctxStorage: ContextStorage, logAnnotated: Boolean): Gauge[Double] =
    make[Double](gauge.set(_, _, _), ctxStorage, logAnnotated)

  private[metrics] def long(gauge: LongGauge, ctxStorage: ContextStorage, logAnnotated: Boolean): Gauge[Long] =
    make[Long](gauge.set(_, _, _), ctxStorage, logAnnotated)

  private def make[A](
    setValue: (A, Attributes, Context) => Unit,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Gauge[A] =
    new Gauge[A] {
      override def record0(value: A, attributes: Attributes, context: Context): Unit =
        setValue(value, attributes, context)

      override def set(value: A, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)
    }

}
