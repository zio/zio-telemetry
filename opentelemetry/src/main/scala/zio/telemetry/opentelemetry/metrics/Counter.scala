package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.{DoubleCounter, LongCounter}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, logAnnotatedAttributes}

/**
 * A Counter instrument that records values of type `A`
 *
 * @tparam A
 *   the type of the recorded value, either [[scala.Long]], [[scala.Double]] or [[scala.Int]]
 */
trait Counter[-A] extends Instrument[A] {

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
  def add(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

  /**
   * Increments a counter by one.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param attributes
   *   set of attributes to associate with the value
   */
  def inc(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]
}

object Counter {

  private[metrics] def long(
    counter: LongCounter,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Counter[Long] =
    new Counter[Long] {

      override def record0(value: Long, attributes: Attributes = Attributes.empty, context: Context): Unit =
        counter.add(value, attributes, context)

      override def add(value: Long, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

      override def inc(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        add(1L, attributes)

    }

  private[metrics] def double(
    counter: DoubleCounter,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Counter[Double] =
    new Counter[Double] {

      override def record0(value: Double, attributes: Attributes = Attributes.empty, context: Context): Unit =
        counter.add(value, attributes, context)

      override def add(value: Double, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

      override def inc(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        add(1.0, attributes)

    }

  private[metrics] def int(instrument: Counter[Long]): Counter[Int] =
    new Counter[Int] {
      override def record0(value: Int, attributes: Attributes, context: Context): Unit =
        instrument.record0(value.toLong, attributes, context)

      override def add(value: Int, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        instrument.add(value.toLong, attributes)

      override def inc(attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        instrument.inc(attributes)
    }

}
