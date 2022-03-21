package zio.telemetry.opentelemetry.example

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

object JaegerTracer {

  def live: RLayer[AppConfig, Tracer] =
    ZLayer
      .service[AppConfig]
      .flatMap(c =>
        (for {
          spanExporter   <- ZIO.attempt(JaegerGrpcSpanExporter.builder().setEndpoint(c.get.tracer.host).build())
          spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
          tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
          openTelemetry  <- ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
          tracer         <- ZIO.succeed(openTelemetry.getTracer("zio.telemetry.opentelemetry.example.JaegerTracer"))
        } yield tracer).toLayer
      )

}
