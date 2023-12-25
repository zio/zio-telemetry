package zio.telemetry.opentelemetry.metrics

import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram

trait Histogram[-A] {

  def record(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]

}

object Histogram {

  private[metrics] def double(histogram: DoubleHistogram, ctxStorage: ContextStorage): Histogram[Double] =
    new Histogram[Double] {

      override def record(value: Double, attributes: Attributes = Attributes.empty): UIO[Unit] =
        ctxStorage.get.map(histogram.record(value, attributes, _))

    }

}
