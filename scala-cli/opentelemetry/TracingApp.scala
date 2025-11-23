//> using scala "3.7.4"
//> using dep dev.zio::zio:2.1.22
//> using dep dev.zio::zio-opentelemetry:4.0.0-RC8
//> using dep io.opentelemetry:opentelemetry-sdk:1.56.0
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.56.0
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.56.0
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.37.0

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.trace.Tracer
import zio.telemetry.opentelemetry.OpenTelemetry

object TracingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.TracingApp"
  val resourceName             = "tracing-app"

  // Prints to stdout in OTLP Json format
  val stdoutTracerProvider: RIO[Scope, SdkTracerProvider] =
    for {
      spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
      tracerProvider <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkTracerProvider
              .builder()
              .setResource(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  val otelSdkLayer: TaskLayer[OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- stdoutTracerProvider
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  override def run =
    ZIO
      .serviceWithZIO[Tracer] { tracer =>
        // Create a root span with a lifetime equal to the runtime of the given ZIO effect.
        tracer.root("root_span", SpanKind.INTERNAL) { span =>
          for {
            // Set an attribute to the current span
            _       <- span.setAttribute("attr1", "value1")
            // Add an event to the current span
            _       <- span.addEvent("Waiting for the user input")
            // Read user input
            message <- Console.readLine
            // Add another event to the current span
            _       <- span.addEvent(s"User typed: $message")
          } yield message
        }
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.tracer(instrumentationScopeName)
      )

}
