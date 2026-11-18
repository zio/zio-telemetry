---
id: opentelemetry-zio-logging
title: "OpenTelemetry ZIO Logging"
---

The `zio-opentelemetry-zio-logging` module connects ZIO Telemetry with [ZIO Logging](https://zio.dev/zio-logging/). It provides [log formats](https://zio.dev/zio-logging/formatting-log-records) that print the trace id and span id of the span that is current when a message is logged, so that console and file logs can be correlated with traces. The module is separate from `zio-opentelemetry` because it depends on `zio-logging`.

To export log records to OpenTelemetry instead, see [Correlating Logs with Traces](how-to/correlate-logs-with-traces.md).

## Installation

Add the module to your `build.sbt`. It depends on `zio-opentelemetry`, so you do not need to add that separately, but you do need an OpenTelemetry SDK and a `zio-logging` appender to produce output:

```scala
libraryDependencies ++= Seq(
  // Span and trace id log formats for ZIO Logging (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opentelemetry-zio-logging" % "@VERSION@"
)
```

## The LogFormats Service

`LogFormats` is a service that provides log formats backed by the current OpenTelemetry span:

```scala
trait LogFormats {
  def traceId: LogFormat
  def spanId: LogFormat
  def traceIdLabel: LogFormat = label("traceId", traceId)
  def spanIdLabel: LogFormat  = label("spanId", spanId)
}
```

The members are:

- `LogFormats#traceId` appends the trace id of the current span as 32 lowercase hexadecimal characters.
- `LogFormats#spanId` appends the span id of the current span as 16 lowercase hexadecimal characters.
- `LogFormats#traceIdLabel` is `LogFormats#traceId` wrapped in a label, which renders as `traceId=<id>`.
- `LogFormats#spanIdLabel` is `LogFormats#spanId` wrapped in a label, which renders as `spanId=<id>`.

Because these are ordinary `LogFormat` values, you combine them with the other formats of ZIO Logging, for example with the `|-|` operator, which joins two formats with a space.

## The ZioLogging Object

`ZioLogging.logFormats` is the only member of the `ZioLogging` object. It builds the `LogFormats` service from a `ContextStorage`, which must be the same storage that your `Tracing` service uses:

```scala
def logFormats: ZLayer[ContextStorage, Nothing, LogFormats]
```

The storage determines where the format reads the current span:

- With `OpenTelemetry.contextZIO`, the format reads the context from the `FiberRef` of the fiber that logs.
- With `OpenTelemetry.contextJVM`, the format reads `Context.current()` of the JVM thread.

Use `OpenTelemetry.contextZIO` unless you use the Java agent, as described in [Context Storage](reference/context-storage.md).

## Behavior Outside a Span

The formats read the context stored for the logging fiber and then take the span from it. When that context holds no span, OpenTelemetry's invalid span is used, and its ids are all zeros: `00000000000000000000000000000000` for the trace id and `0000000000000000` for the span id. When the storage holds no context for the fiber at all, nothing is appended. In either case, logging outside a span never fails.

## Printing Span Ids

The following example defines a console logger that prints the span id and trace id next to each message, and runs an effect inside a root span so that its log message is correlated. It builds a small SDK that prints spans to standard output in OTLP JSON format:

```scala mdoc:compile-only
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.{OpenTelemetry => OtelApi}
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.logging.LogFormat
import zio.logging.LogFormat._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}

object ZioLoggingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.LoggingApp"

  // Prints spans to stdout in OTLP JSON format
  val otelSdkLayer: TaskLayer[OtelApi] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              SdkTracerProvider
                                .builder()
                                .setResource(
                                  Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "logging-app"))
                                )
                                .addSpanProcessor(SimpleSpanProcessor.create(OtlpJsonLoggingSpanExporter.create()))
                                .build()
                            )
                          )
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
                          )
      } yield sdk
    )

  // Replaces the default logger with one that prints the span id and trace id
  val loggingLayer: URLayer[LogFormats, Unit] =
    ZLayer.service[LogFormats].flatMap { env =>
      val logFormats = env.get[LogFormats]
      val format: LogFormat =
        timestamp |-| level |-| label("message", quoted(line)) |-| logFormats.spanIdLabel |-| logFormats.traceIdLabel

      Runtime.removeDefaultLoggers >>> Runtime.addLogger(format.toLogger.map(println(_)))
    }

  override def run: Task[Unit] =
    ZIO
      .serviceWithZIO[Tracing] { tracing =>
        import tracing.aspects._

        // The log message below is printed with the ids of "root_span"
        ZIO.logInfo("handling a request") @@ root("root_span")
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.contextZIO,
        ZioLogging.logFormats,
        loggingLayer
      )
}
```

Each log message written inside the span carries the ids of that span, so the printed line can be matched to the span exported by the OpenTelemetry SDK. To use the same ids in other formats, use `LogFormats#spanId` and `LogFormats#traceId` directly instead of the labels, for example inside a JSON format.
