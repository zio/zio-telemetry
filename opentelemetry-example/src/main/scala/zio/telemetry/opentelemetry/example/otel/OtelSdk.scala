package zio.telemetry.opentelemetry.example.otel

import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api

object OtelSdk {

  def custom(resourceName: String): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- TracerProvider.jaeger(resourceName)
        loggerProvider <- LoggerProvider.seq(resourceName)
        openTelemetry  <- ZIO.acquireRelease(
                            ZIO.attempt(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setLoggerProvider(loggerProvider)
                                .build
                            )
                          )(sdk => ZIO.attempt(sdk.close()).orDie)
      } yield openTelemetry
    )

}
