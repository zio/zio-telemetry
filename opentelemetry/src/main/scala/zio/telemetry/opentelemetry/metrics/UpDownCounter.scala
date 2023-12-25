package zio.telemetry.opentelemetry.metrics

import zio._
import io.opentelemetry.api.common.Attributes
import zio.telemetry.opentelemetry.context.ContextStorage
import io.opentelemetry.api.metrics.LongUpDownCounter

trait UpDownCounter[-A] {

  def add(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]

  def inc(attributes: Attributes = Attributes.empty): UIO[Unit]

  def dec(attributes: Attributes = Attributes.empty): UIO[Unit]

}

object UpDownCounter {

  private[metrics] def long(counter: LongUpDownCounter, ctxStorage: ContextStorage): UpDownCounter[Long] =
    new UpDownCounter[Long] {

      override def add(value: Long, attributes: Attributes = Attributes.empty): UIO[Unit] =
        ctxStorage.get.map(counter.add(value, attributes, _))

      override def inc(attributes: Attributes = Attributes.empty): UIO[Unit] =
        add(1L, attributes)

      override def dec(attributes: Attributes = Attributes.empty): UIO[Unit] =
        add(-1L, attributes)

    }

}
