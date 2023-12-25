package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import zio._

trait ObservableMeasurement[-A] {

  def record(value: A, attributes: Attributes = Attributes.empty): Task[Unit]

}

object ObservableMeasurement {

  private[metrics] def long(om: api.metrics.ObservableLongMeasurement): ObservableMeasurement[Long] =
    new ObservableMeasurement[Long] {

      override def record(value: Long, attributes: Attributes = Attributes.empty): Task[Unit] =
        ZIO.succeed(om.record(value, attributes))

    }

  private[metrics] def double(om: api.metrics.ObservableDoubleMeasurement): ObservableMeasurement[Double] =
    new ObservableMeasurement[Double] {

      override def record(value: Double, attributes: Attributes = Attributes.empty): Task[Unit] =
        ZIO.succeed(om.record(value, attributes))

    }

}
