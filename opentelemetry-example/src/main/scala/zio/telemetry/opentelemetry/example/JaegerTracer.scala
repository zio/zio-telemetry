package zio.telemetry.opentelemetry.example

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

object JaegerTracer {

  def live: RLayer[AppConfig, Tracer] =
    ZLayer(for {
      config         <- ZIO.service[AppConfig]
      spanExporter   <- ZIO.attempt(JaegerGrpcSpanExporter.builder().setEndpoint(config.tracer.host).build())
      spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      tracerProvider <-
        ZIO.succeed(
          SdkTracerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, "opentelemetry-example")))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      openTelemetry  <- ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
      tracer         <- ZIO.succeed(openTelemetry.getTracer("zio.telemetry.opentelemetry.example.JaegerTracer"))
    } yield tracer)

}
