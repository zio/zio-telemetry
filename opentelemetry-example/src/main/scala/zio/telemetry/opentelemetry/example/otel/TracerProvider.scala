package zio.telemetry.opentelemetry.example.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.ResourceAttributes
import zio._
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter

object TracerProvider {

  /**
   * https://www.jaegertracing.io/
   */
  def jaeger(resourceName: String): Task[SdkTracerProvider] =
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
    } yield tracerProvider

  /**
   * https://fluentbit.io/
   */
  def fluentbit(resourceName: String): Task[SdkTracerProvider] =
    for {
      spanExporter   <- ZIO.attempt(OtlpHttpSpanExporter.builder().build())
      spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      tracerProvider <-
        ZIO.attempt(
          SdkTracerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
            .addSpanProcessor(spanProcessor)
            .build()
        )
    } yield tracerProvider

}
