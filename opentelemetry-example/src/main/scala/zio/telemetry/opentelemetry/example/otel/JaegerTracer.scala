package zio.telemetry.opentelemetry.example.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

/**
 * https://www.jaegertracing.io/
 */
object JaegerTracer {

  def live(resourceName: String, instrumentationScopeName: String): RLayer[AppConfig, Tracer] =
    ZLayer(
      for {
        spanExporter   <- ZIO.attempt(OtlpGrpcSpanExporter.builder().build())
        spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
        tracerProvider <-
          ZIO.attempt(
            SdkTracerProvider
              .builder()
              .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        openTelemetry  <- ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
        tracer         <- ZIO.succeed(openTelemetry.getTracer(instrumentationScopeName))
      } yield tracer
    )

}
