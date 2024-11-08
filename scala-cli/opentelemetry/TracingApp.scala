//> using scala "3.5.2"
//> using dep dev.zio::zio:2.1.12
//> using dep dev.zio::zio-opentelemetry:3.0.0
//> using dep io.opentelemetry:opentelemetry-sdk:1.43.0
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.43.0
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.43.0
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.22.0-alpha

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage

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
              .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  val otelSdkLayer: TaskLayer[api.OpenTelemetry] =
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
      .serviceWithZIO[Tracing] { tracing =>
        val logic = for {
          // Set an attribute to the current span
          _       <- tracing.setAttribute("attr1", "value1")
          // Add an event to the current span
          _       <- tracing.addEvent("Waiting for the user input")
          // Read user input
          message <- Console.readLine
          // Add another event to the current span
          _       <- tracing.addEvent(s"User typed: $message")
        } yield message

        // Create a root span with a lifetime equal to the runtime of the given ZIO effect.
        // We use ZIO Aspect's @@ syntax here just for the sake of example.
        logic @@ tracing.aspects.root("root_span", SpanKind.INTERNAL)
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.contextZIO
      )

}
