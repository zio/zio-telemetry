package zio.telemetry.opentelemetry.metrics.internal

import zio.metrics.{MetricKey, MetricKeyType, MetricListener}
import zio.{Unsafe, _}

import java.time.Instant

object OtelMetricListener {

  def zioMetrics: URLayer[InstrumentRegistry, MetricListener] =
    ZLayer(
      for {
        registry <- ZIO.service[InstrumentRegistry]
      } yield new MetricListener {

        override def modifyGauge(key: MetricKey[MetricKeyType.Gauge], value: Double)(implicit unsafe: Unsafe): Unit =
          registry.getGauge(key).incrementBy(value)

        override def updateGauge(key: MetricKey[MetricKeyType.Gauge], value: Double)(implicit unsafe: Unsafe): Unit =
          registry.getGauge(key).set(value)

        override def updateHistogram(key: MetricKey[MetricKeyType.Histogram], value: Double)(implicit
          unsafe: Unsafe
        ): Unit =
          registry.getHistorgram(key).record0(value, attributes(key.tags))

        override def updateCounter(key: MetricKey[MetricKeyType.Counter], value: Double)(implicit
          unsafe: Unsafe
        ): Unit =
          registry.getCounter(key).record0(value.toLong, attributes(key.tags))

        override def updateFrequency(key: MetricKey[MetricKeyType.Frequency], value: String)(implicit
          unsafe: Unsafe
        ): Unit =
          ()

        override def updateSummary(key: MetricKey[MetricKeyType.Summary], value: Double, instant: Instant)(implicit
          unsafe: Unsafe
        ): Unit =
          ()

      }
    )

}
