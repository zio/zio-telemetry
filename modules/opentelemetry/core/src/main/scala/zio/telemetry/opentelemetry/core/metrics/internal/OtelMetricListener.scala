package zio.telemetry.opentelemetry.core.metrics.internal

import zio.Unsafe
import zio.metrics.{MetricKey, MetricKeyType, MetricLabel, MetricListener}

import java.time.Instant

private[opentelemetry] object OtelMetricListener {

  def zioMetrics(registry: InstrumentRegistry): MetricListener =
    new MetricListener {

      override def modifyGauge(key: MetricKey[MetricKeyType.Gauge], value: Double)(implicit unsafe: Unsafe): Unit =
        registry.getObservableGauge(key).incrementBy(value)

      override def updateGauge(key: MetricKey[MetricKeyType.Gauge], value: Double)(implicit unsafe: Unsafe): Unit =
        registry.getGauge(key).record0(value, attributes(key.tags))

      override def updateHistogram(key: MetricKey[MetricKeyType.Histogram], value: Double)(implicit
        unsafe: Unsafe
      ): Unit =
        registry.getHistogram(key).record0(value, attributes(key.tags))

      override def updateCounter(key: MetricKey[MetricKeyType.Counter], value: Double)(implicit
        unsafe: Unsafe
      ): Unit =
        registry.getCounter(key).record0(value.toLong, attributes(key.tags))

      override def updateFrequency(key: MetricKey[MetricKeyType.Frequency], value: String)(implicit
        unsafe: Unsafe
      ): Unit =
        registry
          .getCounter(key.copy[MetricKeyType.Counter](key.name, MetricKeyType.Counter, key.tags))
          .record0(1L, attributes(key.tags + MetricLabel("bucket", value)))

      // TODO: implement. One of the candidates to look at for inspiration is the micrometer library.
      override def updateSummary(key: MetricKey[MetricKeyType.Summary], value: Double, instant: Instant)(implicit
        unsafe: Unsafe
      ): Unit =
        ()

    }

}
