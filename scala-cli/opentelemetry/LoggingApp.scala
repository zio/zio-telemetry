//> using scala "3.5.2"
//> using dep dev.zio::zio:2.1.13
//> using dep dev.zio::zio-opentelemetry:3.0.0
//> using dep io.opentelemetry:opentelemetry-sdk:1.44.1
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.44.1
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.44.1
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.22.0-alpha

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingLogRecordExporter
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage

object LoggingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.LoggingApp"
  val resourceName             = "logging-app"

  // Prints to stdout in OTLP Json format
  val stdoutLoggerProvider: RIO[Scope, SdkLoggerProvider] =
    for {
      logRecordExporter  <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingLogRecordExporter.create()))
      logRecordProcessor <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleLogRecordProcessor.create(logRecordExporter)))
      loggerProvider     <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkLoggerProvider
              .builder()
              .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .addLogRecordProcessor(logRecordProcessor)
              .build()
          )
        )
    } yield loggerProvider

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
        loggerProvider <- stdoutLoggerProvider
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setLoggerProvider(loggerProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  override def run =
    ZIO
      .serviceWithZIO[Tracing] { tracing =>
        val logic = for {
          // Read user input
          message <- Console.readLine
          // Propagate a ZIO.logInfo message as an OTEL log signal and log annotations as log record attributes
          _       <- ZIO.logAnnotate("correlated", "true")(
                       ZIO.logInfo(s"User message: $message")
                     )
        } yield ()

        // All log messages produced by `logic` will be correlated with a "root_span" automatically
        logic @@ tracing.aspects.root("root_span")
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.logging(instrumentationScopeName),
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.contextZIO
      )

}
