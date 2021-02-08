package zio.telemetry.opentelemetry.example

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import zio._
import zio.telemetry.opentelemetry.example.config.{ Config, Configuration }
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

object JaegerTracer {

  def live: RLayer[Configuration, Has[Tracer]] =
    ZLayer.fromServiceM((conf: Config) =>
      for {
        spanExporter   <- UIO(JaegerGrpcSpanExporter.builder().setEndpoint(conf.tracer.host).build())
        spanProcessor  <- UIO(SimpleSpanProcessor.create(spanExporter))
        tracerProvider <- UIO(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
        openTelemetry  <- UIO(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
        tracer         <- UIO(openTelemetry.getTracer("zio.telemetry.opentelemetry.example.JaegerTracer"))

      } yield tracer
    )

}
