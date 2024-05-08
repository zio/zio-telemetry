package zio.telemetry.opentelemetry.example.otel

import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api

object OtelSdk {

  def custom(resourceName: String): RLayer[Any with OtelSdkOutput, api.OpenTelemetry] = {

    for {
      otelSdkOutput <- ZLayer(ZIO.service[OtelSdkOutput])
      openTelemetry <- OpenTelemetry.custom(
        for {
          tracerProvider <- otelSdkOutput.get match {
            case OtelSdkOutput.Stdout => TracerProvider.stdout(resourceName)
            case OtelSdkOutput.JaegerSeq => TracerProvider.jaeger(resourceName)
          }
          meterProvider <- MeterProvider.stdout(resourceName)
          loggerProvider <- otelSdkOutput.get match {
            case OtelSdkOutput.Stdout => LoggerProvider.stdout(resourceName)
            case OtelSdkOutput.JaegerSeq => LoggerProvider.seq(resourceName)
          }
          openTelemetry <- ZIO.fromAutoCloseable(
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
    } yield openTelemetry
  }

}
