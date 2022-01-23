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
    ZLayer.service[AppConfig].flatMap(c =>
      (for {
        spanExporter <- Task(JaegerGrpcSpanExporter.builder().setEndpoint(c.get.tracer.host).build())
        spanProcessor <- UIO(SimpleSpanProcessor.create(spanExporter))
        tracerProvider <- UIO(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
        openTelemetry <- UIO(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
        tracer <- UIO(openTelemetry.getTracer("zio.telemetry.opentelemetry.example.JaegerTracer"))
      } yield tracer).toLayer
    )

}
