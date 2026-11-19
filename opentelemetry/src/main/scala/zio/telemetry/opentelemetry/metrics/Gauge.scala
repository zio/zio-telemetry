package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.{DoubleGauge, LongGauge}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, logAnnotatedAttributes}

/** Records a current value of type `A` using the context from `ContextStorage`. */
trait Gauge[-A] extends Instrument[A] {

  def set(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): UIO[Unit]

}

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

  private[metrics] def int(instrument: Gauge[Long]): Gauge[Int] =
    new Gauge[Int] {
      override def record0(value: Int, attributes: Attributes, context: Context): Unit =
        instrument.record0(value.toLong, attributes, context)

      override def set(value: Int, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        instrument.set(value.toLong, attributes)
    }

}
