package zio.telemetry.opentelemetry.example.otel

import io.opentelemetry.sdk.OpenTelemetrySdk
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

object OtelSdk {

  def custom(resourceName: String): TaskLayer[OpenTelemetry] =
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
