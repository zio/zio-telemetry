package zio.telemetry.opentelemetry.metrics

import zio._
import io.opentelemetry.api.metrics.LongCounter
import zio.telemetry.opentelemetry.context.ContextStorage
import io.opentelemetry.api.common.Attributes

trait Counter[A] {

  def add(value: A): Task[Unit]

  def inc: Task[Unit]
}

object Counter {

  def long(counter: LongCounter, ctxStorage: ContextStorage): Counter[Long] = new Counter[Long] {

    override def add(value: Long): Task[Unit] =
      ctxStorage.get.map(counter.add(value, Attributes.empty(), _))

    override def inc: Task[Unit] =
      add(1L)

  }

}
