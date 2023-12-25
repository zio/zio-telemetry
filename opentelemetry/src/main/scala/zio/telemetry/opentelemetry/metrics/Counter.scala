package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

trait Counter[-A] {

  def add(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]

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
