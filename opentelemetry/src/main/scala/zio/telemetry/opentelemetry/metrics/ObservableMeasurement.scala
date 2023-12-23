package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio._

trait ObservableMeasurement[-A] {

  def record(value: A): Task[Unit]

}

object ObservableMeasurement {

  private[metrics] def long(om: api.metrics.ObservableLongMeasurement): ObservableMeasurement[Long] =
    new ObservableMeasurement[Long] {

      override def record(value: Long): Task[Unit] =
        ZIO.succeed(om.record(value))

    }

}
