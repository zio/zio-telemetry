package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, logAnnotatedAttributes}

/**
 * A UpDownCounter instrument that records values of type `A`
 *
 * @tparam A
 *   according to the specification, it can be either [[scala.Long]] or [[scala.Double]] type
 */
trait UpDownCounter[-A] extends Instrument[A] {

  /**
   * Records a value.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param value
   *   increment amount. May be positive, negative or zero
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

  /**
   * Decrements a counter by one.
   *
   * It uses the context taken from the [[zio.telemetry.opentelemetry.context.ContextStorage]] to associate with this
   * measurement.
   *
   * @param attributes
   *   set of attributes to associate with the value
   */
  def dec(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

}

object UpDownCounter {

  private[metrics] def long(
    counter: LongUpDownCounter,
    ctxStorage: ContextStorage,
    logAnnotated: Boolean
  ): UpDownCounter[Long] =
    new UpDownCounter[Long] {

      override def record0(value: Long, attributes: Attributes = Attributes.empty, context: Context): Unit =
        counter.add(value, attributes, context)

      override def add(value: Long, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        for {
          annotated <- logAnnotatedAttributes(attributes, logAnnotated)
          ctx       <- ctxStorage.get
        } yield record0(value, annotated, ctx)

      override def inc(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        add(1L, attributes)

      override def dec(attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit] =
        add(-1L, attributes)

    }

}
