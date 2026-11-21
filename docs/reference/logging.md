---
id: logging
title: "Logging"
---

The logging bridge forwards every `ZIO.log*` call to the OpenTelemetry Logs API as a log record. The record carries the message, the severity, the ZIO log annotations as attributes, and the OpenTelemetry context of the fiber, which links the record to the active trace and span.

## Creating the Logging Layer

`OpenTelemetry.logging` installs the bridge as a ZIO logger for the lifetime of the layer. The layer has no output service: it exists for its effect, and the logger is removed when the layer's scope closes:

```scala
object OpenTelemetry {
  def logging(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[api.OpenTelemetry & ContextStorage, Unit]
}
```

`instrumentationScopeName` identifies the logger in the exported records. Only messages at `logLevel` or above are forwarded, and the default is `LogLevel.Info`. The layer asks the OpenTelemetry instance for its log bridge, so the instance must have a `LoggerProvider` configured, which is the case for an SDK built with a log record processor. To enable the bridge in a program:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

val loggingLayer: ULayer[Unit] =
  (OpenTelemetry.noop ++ OpenTelemetry.contextZIO) >>> OpenTelemetry.logging("com.example.shop", LogLevel.Debug)

val program: UIO[Unit] =
  ZIO.logInfo("Order accepted").provide(loggingLayer)
```

The same layer is available as `Logging.live(instrumentationScopeName, logLevel)` in `zio.telemetry.opentelemetry.logging`, which takes a `LoggerProvider` instead of an `OpenTelemetry` instance.

## Record Contents

Each log call produces one OpenTelemetry log record.

| Record Field  | Value                                                                                                                                              |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Body          | The log message                                                                                                                                    |
| Severity      | `Trace`, `Debug`, `Info`, `Warning`, `Error` and `Fatal` map to the OpenTelemetry severities `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and `FATAL` |
| Severity text | The ZIO log level label, such as `INFO` or `WARN`                                                                                                  |
| Attributes    | ZIO log annotations, one string attribute per annotation                                                                                           |
| Context       | The OpenTelemetry context of the fiber, which supplies the trace id and span id                                                                    |

The `Cause` and the log spans of the ZIO log call are not part of the record. Log levels outside the six listed above are exported with an undefined severity number.

## Log Annotations as Attributes

Annotations added with `ZIO.logAnnotate` become attributes of the records emitted inside the annotated effect. To attach a request identifier to a log record:

```scala mdoc:compile-only
import zio._

val annotated: UIO[Unit] =
  ZIO.logAnnotate("request.id", "r-42") {
    ZIO.logInfo("Processing request")
  }
```

## Correlating Logs with the Current Span

The record takes its context from the same storage that `Tracing` uses. With the `FiberRef` storage that `OpenTelemetry.contextZIO` provides, the bridge reads the context stored in the fiber's `FiberRef`. With the native storage that `OpenTelemetry.contextJVM` provides, it reads `Context.current()`. In both cases a log call made inside `Tracing#span` is exported with the trace id and span id of that span, and a call outside any span carries the all-zero invalid identifiers. The same `ContextStorage` must be shared by the logging and tracing layers. For the recipe that links logs and traces, see [How to Correlate Logs with Traces](../how-to/correlate-logs-with-traces.md). If you already use zio-logging, see [ZIO Logging Integration](../opentelemetry-zio-logging.md).

## Related Pages

The layers that build the OpenTelemetry instance and the context storage are described in [OpenTelemetry Layers](opentelemetry-layers.md) and [Context Storage](context-storage.md). Span creation is documented in [Tracing](tracing.md).
