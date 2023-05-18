package zio.telemetry.opentelemetry.instrumentation.example

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import zio._
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

object JaegerTracer {

  def live: RLayer[AppConfig, Tracer] =
    ZLayer {
      for {
        config <- ZIO.service[AppConfig]
        tracer <- makeTracer(config.tracer.host)
      } yield tracer
    }

  def makeTracer(host: String): Task[Tracer] =
    for {
      spanExporter   <- ZIO.attempt(JaegerGrpcSpanExporter.builder().setEndpoint(host).build())
      spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      tracerProvider <-
        ZIO.attempt(
          SdkTracerProvider
            .builder()
            .setResource(
              Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, "opentelemetry-instrumentation-example"))
            )
            .addSpanProcessor(spanProcessor)
            .build()
        )
      openTelemetry  <- ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
      tracer         <- ZIO.succeed(openTelemetry.getTracer("zio.telemetry.opentelemetry.example.instrumentation.JaegerTracer"))
    } yield tracer

}
