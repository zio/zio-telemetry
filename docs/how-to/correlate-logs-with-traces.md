---
id: correlate-logs-with-traces
title: "How to Correlate Logs with Traces"
description: "Attach trace and span ids to ZIO log messages, either as OpenTelemetry log records or as fields in your console logs."
keywords:
  - "OpenTelemetry"
  - "ZIO Logging"
  - "trace id"
  - "log correlation"
---

In this guide, we will make every `ZIO.log*` call carry the id of the trace and span it runs in. By the end, you can jump from a span in your tracing backend to the exact log lines it produced, and from a log line back to its trace.

There are two ways to do it, and they can be used independently. We can export the logs as OpenTelemetry log records that carry the span context automatically, or we can print the `traceId` and `spanId` in the log format of ZIO Logging.

## The Problem

A request that crosses several services and fibers leaves log lines scattered across files and processes. Without a shared identifier, finding the logs that belong to a slow span means guessing from timestamps and message text.

ZIO log calls do not know about spans. Passing the ids by hand would mean threading them through every function, and putting them in `ZIO.logAnnotate` would mean reading the current span context before every log call. ZIO Telemetry does that lookup for us in two places: when it builds the log record and when it formats a log line.

## Prerequisites

The log bridge lives in the `zio-opentelemetry` module and the log formats live in `zio-opentelemetry-zio-logging`, which brings ZIO Logging along. We add both:

```scala
libraryDependencies ++= Seq(
  // Tracing and the OpenTelemetry log bridge
  "dev.zio" %% "zio-opentelemetry"             % "@VERSION@",
  // Trace and span ids as ZIO Logging formats
  "dev.zio" %% "zio-opentelemetry-zio-logging" % "@VERSION@"
)
```

We assume that a `Tracing` service is already set up, see [How to Export Signals with OTLP](export-signals-with-otlp.md). The log bridge additionally needs a `LoggerProvider` configured on the `OpenTelemetry` SDK instance you pass to the layers.

The examples share these imports:

```scala mdoc:silent
import io.opentelemetry.api
import zio._
import zio.logging.{ConsoleLoggerConfig, LogFormat, consoleLogger}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}
```

## Exporting Logs as OpenTelemetry Records

The `OpenTelemetry.logging` layer registers a ZIO logger that turns every log call into an OpenTelemetry log record and hands it to the `LoggerProvider` of the SDK. Each record gets the message as its body, the ZIO log level as its severity, the ZIO log annotations as attributes, and the context of the current span. The backend then links the record to the trace and span.

We build the layer with an instrumentation scope name, and an optional minimum level which defaults to `LogLevel.Info`:

```scala mdoc:silent
def logBridge: URLayer[api.OpenTelemetry & ContextStorage, Unit] =
  OpenTelemetry.logging("com.example.orders", LogLevel.Debug)
```

Records below the given level are dropped by this logger. The layer adds its logger to the loggers already installed, so console output continues unless you remove the default loggers with `Runtime.removeDefaultLoggers`.

The following program logs inside a span. We wire the layers so that `OpenTelemetry.logging` and `OpenTelemetry.tracing` share the same `ContextStorage`, which is what lets the logger see the current span. `Unit` is listed among the outputs so that the logging layer is built even though nothing consumes it:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    ZIO.logInfo("charging the card") @@ tracing.aspects.root("checkout")
  }

val bridged: ZIO[Any, Nothing, Unit] =
  program.provideLayer(
    ZLayer.make[Tracing & Unit](
      OpenTelemetry.noop, // stands in for your SDK layer
      OpenTelemetry.contextZIO,
      OpenTelemetry.tracing("com.example.orders"),
      logBridge
    )
  )
```

If your application runs with the Java agent, use `OpenTelemetry.contextJVM` and `OpenTelemetry.global` instead, as described in [How to Use the Java Agent](use-the-java-agent.md).

## Printing Trace Ids in Console Logs

Logs that go to a file or the console, for example because your platform collects stdout, need the ids in the text itself. The `ZioLogging.logFormats` layer provides a `LogFormats` service whose members are ready-made `LogFormat` values for ZIO Logging:

- `LogFormats#traceId` and `LogFormats#spanId` print the bare id.
- `LogFormats#traceIdLabel` and `LogFormats#spanIdLabel` print the id with a `traceId=` or `spanId=` label.

The layer reads the current span from the same `ContextStorage` as `Tracing`, so it depends on it.

We combine the labels with the usual ZIO Logging building blocks and install the console logger. `Runtime.removeDefaultLoggers` replaces the default logger so the line is not printed twice:

```scala mdoc:silent
def loggingLayer: URLayer[LogFormats, Unit] =
  ZLayer.fromZIO(
    ZIO.serviceWith[LogFormats] { logFormats =>
      val format =
        LogFormat.timestamp |-|
          LogFormat.level |-|
          LogFormat.label("message", LogFormat.quoted(LogFormat.line)) |-|
          logFormats.spanIdLabel |-|
          logFormats.traceIdLabel

      Runtime.removeDefaultLoggers >>>
        consoleLogger(ConsoleLoggerConfig.default.copy(format = format))
    }
  ).flatten
```

Now we assemble the application. `Tracing`, the log formats and the logger must all use one `ContextStorage`, and the logger layer has to be built even though nobody asks for its output, so we list `Unit` among the requested outputs:

```scala mdoc:compile-only
val checkout: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    ZIO.logInfo("charging the card") @@ tracing.aspects.root("checkout")
  }

def observability: ULayer[Tracing & Unit] =
  ZLayer.make[Tracing & Unit](
    OpenTelemetry.noop,
    OpenTelemetry.contextZIO,
    OpenTelemetry.tracing("com.example.orders"),
    ZioLogging.logFormats,
    loggingLayer
  )

val app: ZIO[Any, Nothing, Unit] =
  checkout.provideLayer(observability)
```

The log line inside `checkout` now ends with the span id and trace id of the `checkout` span. Log calls made outside any span have no valid span context, so their ids are not meaningful.

:::tip
`ZioLogging.logFormats` works with both `OpenTelemetry.contextZIO` and `OpenTelemetry.contextJVM`. With the latter it reads the span from OpenTelemetry's own thread-local context, which is what the Java agent maintains.
:::

## Copying Log Annotations onto Spans

Correlation also works in the other direction. When we pass `logAnnotated = true` to `OpenTelemetry.tracing`, every span created afterwards gets the current ZIO log annotations as string attributes, so annotations such as a user id appear both in the logs and on the span:

```scala mdoc:silent
def annotatedTracing: URLayer[api.OpenTelemetry & ContextStorage, Tracing] =
  OpenTelemetry.tracing("com.example.orders", logAnnotated = true)
```

Attributes passed explicitly to the span override annotations with the same key.

## Going Further

The [OpenTelemetry ZIO Logging](../opentelemetry-zio-logging.md) page describes the integration module, and [Logging](../reference/logging.md) covers the log bridge reference. For a full application that exports logs next to traces, see the [OpenTelemetry example](../opentelemetry-example.md).
