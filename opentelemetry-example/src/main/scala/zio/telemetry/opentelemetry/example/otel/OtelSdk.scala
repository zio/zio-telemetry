package zio.telemetry.opentelemetry.example.otel

import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api

object OtelSdk {

  def custom(resourceName: String): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- TracerProvider.stdout(resourceName)
        meterProvider  <- MeterProvider.stdout(resourceName)
        loggerProvider <- LoggerProvider.stdout(resourceName)
        openTelemetry  <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setMeterProvider(meterProvider)
                                .setLoggerProvider(loggerProvider)
                                .build
                            )
                          )
      } yield openTelemetry
    )

}
