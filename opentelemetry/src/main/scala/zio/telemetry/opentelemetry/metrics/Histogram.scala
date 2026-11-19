package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.{DoubleHistogram, LongHistogram}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, logAnnotatedAttributes}

/**
 * A Histogram instrument that records values of type `A`
 *
 * @tparam A
 *   the type of the recorded value, either [[scala.Long]], [[scala.Double]] or [[scala.Int]]
 */
trait Histogram[-A] extends Instrument[A] {

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
  def record(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

}

object Histogram {

  private[metrics] def double(
    histogram: DoubleHistogram,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Histogram[Double] =
    new Histogram[Double] {

      override def record0(value: Double, attributes: Attributes = Attributes.empty, context: Context): Unit =
        histogram.record(value, attributes, context)

      override def record(value: Double, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

    }

  private[metrics] def long(
    histogram: LongHistogram,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Histogram[Long] =
    new Histogram[Long] {

      override def record0(value: Long, attributes: Attributes = Attributes.empty, context: Context): Unit =
        histogram.record(value, attributes, context)

      override def record(value: Long, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

    }

  private[metrics] def int(instrument: Histogram[Long]): Histogram[Int] =
    new Histogram[Int] {
      override def record0(value: Int, attributes: Attributes, context: Context): Unit =
        instrument.record0(value.toLong, attributes, context)

      override def record(value: Int, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        instrument.record(value.toLong, attributes)
    }

}
