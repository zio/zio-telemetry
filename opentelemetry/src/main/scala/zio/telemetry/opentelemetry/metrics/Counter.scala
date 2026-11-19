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
 *   according to the specification, it can be either [[scala.Long]] or [[scala.Double]] type
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
    make[Long](counter.add(_, _, _), ctxStorage, logAnnotated, 1L)

  private[metrics] def double(
    counter: DoubleCounter,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): Counter[Double] =
    make[Double](counter.add(_, _, _), ctxStorage, logAnnotated, 1.0)

  private def make[A](
    recordValue: (A, Attributes, Context) => Unit,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean,
    increment: A
  ): Counter[A] =
    new Counter[A] {
      override def record0(value: A, attributes: Attributes, context: Context): Unit =
        recordValue(value, attributes, context)

      override def add(value: A, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

      override def inc(attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        add(increment, attributes)
    }

}
