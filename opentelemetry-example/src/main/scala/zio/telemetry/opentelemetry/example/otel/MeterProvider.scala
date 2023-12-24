package zio.telemetry.opentelemetry.example.otel

import zio._
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingMetricExporter

object MeterProvider {

  /**
   * Prints to stdout in OTLP Json format
   */
  def stdout(resourceName: String): RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
      metricReader   <-
        ZIO.fromAutoCloseable(ZIO.succeed(PeriodicMetricReader.builder(metricExporter).setInterval(5.second).build()))
      meterProvider  <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkMeterProvider
              .builder()
              .registerMetricReader(metricReader)
              .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .build()
          )
        )
    } yield meterProvider

}
