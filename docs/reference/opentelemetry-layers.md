---
id: opentelemetry-layers
title: "OpenTelemetry Layers"
sidebar_label: "OpenTelemetry Layers"
---

The `zio.telemetry.opentelemetry.OpenTelemetry` object is the entry point of the `zio-opentelemetry` module. It contains no state of its own; it is a collection of `ZLayer` constructors that build the services you use in your application: the underlying Java `io.opentelemetry.api.OpenTelemetry` instance, the `ContextStorage`, and the `Tracing`, `Meter`, `Baggage` and logging services.

## Layer Overview

The layers fall into three groups. The first group provides the Java `OpenTelemetry` instance, the second provides a `ContextStorage`, and the third builds ZIO services on top of those two.

| Layer                         | Type                                                                                     | Provides                         |
|-------------------------------|------------------------------------------------------------------------------------------|----------------------------------|
| `OpenTelemetry.global`        | `TaskLayer[api.OpenTelemetry]`                                                           | The globally registered instance |
| `OpenTelemetry.custom`        | `TaskLayer[api.OpenTelemetry]`                                                           | A scoped, programmatic instance  |
| `OpenTelemetry.noop`          | `ULayer[api.OpenTelemetry]`                                                              | A no-op instance                 |
| `OpenTelemetry.contextZIO`    | `ULayer[ContextStorage]`                                                                 | `FiberRef`-backed storage        |
| `OpenTelemetry.contextJVM`    | `ULayer[ContextStorage]`                                                                 | `ThreadLocal`-backed storage     |
| `OpenTelemetry.tracing`       | `URLayer[api.OpenTelemetry & ContextStorage, Tracing]`                                   | `Tracing`                        |
| `OpenTelemetry.metrics`       | `URLayer[api.OpenTelemetry & ContextStorage, Meter & Instrument.Builder]`                | `Meter` and `Instrument.Builder` |
| `OpenTelemetry.zioMetrics`    | `URLayer[Instrument.Builder, Unit]`                                                      | ZIO runtime metrics bridge       |
| `OpenTelemetry.logging`       | `URLayer[api.OpenTelemetry & ContextStorage, Unit]`                                      | `ZIO.log*` as OTEL log records   |
| `OpenTelemetry.baggage`       | `URLayer[ContextStorage, Baggage]`                                                       | `Baggage`                        |

In this page `api.OpenTelemetry` stands for `io.opentelemetry.api.OpenTelemetry` imported as `import io.opentelemetry.api`, to distinguish it from the `OpenTelemetry` object of this library. The `Instrument.Builder` type is `zio.telemetry.opentelemetry.metrics.internal.Instrument.Builder`.

## How the Layers Fit Together

Every ZIO service in the module depends on two things: a Java `OpenTelemetry` instance (except `Baggage`, which only needs a `ContextStorage`) and a `ContextStorage`. We therefore always supply one layer from each of the first two groups and combine them with the service layers we need. `ZIO#provide` wires the dependencies automatically:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing

val program: ZIO[Tracing & Baggage, Nothing, Unit] =
  ZIO.unit

val runnable: UIO[Unit] =
  program.provide(
    OpenTelemetry.noop,
    OpenTelemetry.contextZIO,
    OpenTelemetry.tracing("com.example.app"),
    OpenTelemetry.baggage()
  )
```

All the services share the same `ContextStorage` instance, which is what makes a span, a baggage entry, a log record and a metric data point see the same current context. Providing `OpenTelemetry.contextZIO` once in a single `provide` call guarantees this, because ZIO memoizes a layer within one wiring. If you build separate layer graphs, make sure they share the same storage layer value.

## Providing the OpenTelemetry Instance

### OpenTelemetry#global

```scala
val global: TaskLayer[api.OpenTelemetry]
```

Returns the instance registered with `GlobalOpenTelemetry.get()`. It fails with a `Throwable` if obtaining the instance throws. Use it together with the SDK autoconfiguration module or the OpenTelemetry Java agent, which register the global instance for you. When the agent is used, combine it with `OpenTelemetry.contextJVM`.

### OpenTelemetry#custom

```scala
def custom(zio: => ZIO[Scope, Throwable, api.OpenTelemetry]): TaskLayer[api.OpenTelemetry]
```

Builds the layer from a scoped effect, so we are in control of how the SDK is configured and the instance is released when the layer's scope closes. The only parameter, `zio`, is the scoped effect that creates the configured instance. A minimal example that builds an SDK without any exporter looks like this:

```scala mdoc:compile-only
import io.opentelemetry.api
import io.opentelemetry.sdk.OpenTelemetrySdk
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

val sdkLayer: TaskLayer[api.OpenTelemetry] =
  OpenTelemetry.custom(
    ZIO.fromAutoCloseable(ZIO.succeed(OpenTelemetrySdk.builder().build()))
  )
```

In a real application you register tracer, meter and logger providers on the builder; see [How to Export Signals with OTLP](../how-to/export-signals-with-otlp.md).

### OpenTelemetry#noop

```scala
val noop: ULayer[api.OpenTelemetry]
```

Provides `api.OpenTelemetry.noop()`. Every signal created from it is discarded, which makes it useful when you want the instrumented code to run without any collector, for example in unit tests that do not assert on telemetry.

## Providing a ContextStorage

### OpenTelemetry#contextZIO

```scala
def contextZIO: ULayer[ContextStorage]
```

Creates a `ContextStorage` backed by a `ZIO` `FiberRef`. Use it when you do not use automatic instrumentation. The context is inherited by child fibers and restored when a scope ends, in the same way as any other `FiberRef`.

### OpenTelemetry#contextJVM

```scala
def contextJVM: ULayer[ContextStorage]
```

Creates a `ContextStorage` backed by OpenTelemetry's own `Context.current()`, which is stored in a `ThreadLocal`. Use it when the OpenTelemetry Java agent instruments your application and you want ZIO spans to be correlated with spans the agent creates. See [Context Storage](context-storage.md) for the trade-offs between the two.

## Building Services

### OpenTelemetry#tracing

```scala
def tracing(
  instrumentationScopeName: String,
  instrumentationVersion: Option[String] = None,
  schemaUrl: Option[String] = None,
  logAnnotated: Boolean = false
): URLayer[api.OpenTelemetry & ContextStorage, Tracing]
```

Creates a `Tracing` service. It asks the Java instance for a `Tracer` built with the given scope name, then wraps the tracer into `Tracing`.

The layer takes four parameters:

- `instrumentationScopeName` is the name that uniquely identifies the instrumentation scope, such as a library, a package or a fully qualified class name.
- `instrumentationVersion` is the optional version of the scope, for example `Some("1.0.0")`.
- `schemaUrl` is the optional schema URL of the scope.
- `logAnnotated` copies the current ZIO log annotations into the attributes of every span when it is `true`. It is `false` by default.

See [Tracing](tracing.md) for the service API.

### OpenTelemetry#metrics

```scala
def metrics(
  instrumentationScopeName: String,
  instrumentationVersion: Option[String] = None,
  schemaUrl: Option[String] = None,
  logAnnotated: Boolean = false
): URLayer[api.OpenTelemetry & ContextStorage, Meter & Instrument.Builder]
```

Creates a `Meter` and an `Instrument.Builder` from a Java `Meter` built with the given scope name. The layer is named `metrics`; there is no `OpenTelemetry.meter`. The parameters have the same meaning as for `OpenTelemetry#tracing`, with `logAnnotated` applying to the attributes of every recorded data point.

The `Instrument.Builder` output is what `OpenTelemetry#zioMetrics` consumes. If you only need the `Meter`, you do not have to ask for the builder in your environment. See [Metrics](metrics.md) for the service API.

### OpenTelemetry#zioMetrics

```scala
def zioMetrics: URLayer[Instrument.Builder, Unit]
```

Forwards ZIO's own metrics to OpenTelemetry. When the layer is built, it enables ZIO runtime metrics, creates an instrument registry and registers a ZIO `MetricListener` that translates ZIO metrics into OpenTelemetry instruments. The listener is removed when the layer's scope closes. The layer provides `Unit` and takes no parameters.

By default only the ZIO runtime metrics and the metrics you define with ZIO's metric API are propagated. For JVM metrics, additionally provide `DefaultJvmMetrics.live.unit`. Because the layer needs an `Instrument.Builder`, it follows `OpenTelemetry#metrics` in the wiring:

```scala mdoc:compile-only
import io.opentelemetry.api
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage

val zioMetricsLayer: URLayer[api.OpenTelemetry & ContextStorage, Unit] =
  OpenTelemetry.metrics("com.example.app") >>> OpenTelemetry.zioMetrics
```

### OpenTelemetry#logging

```scala
def logging(
  instrumentationScopeName: String,
  logLevel: LogLevel = LogLevel.Info
): URLayer[api.OpenTelemetry & ContextStorage, Unit]
```

Installs a ZIO logger that turns every `ZIO.log*` call into an OpenTelemetry log record, so it requires no service in your environment, and the layer provides `Unit`. The record carries the message as its body, the ZIO log level as severity, all ZIO log annotations as string attributes, and the OpenTelemetry context of the fiber that logged, which correlates it with the surrounding span. The logger is added for the scope of the layer.

The `instrumentationScopeName` parameter names the log scope. The `logLevel` parameter is the minimum level that is forwarded: a record is emitted only when its level is greater than or equal to it. It defaults to `LogLevel.Info`. See [Logging](logging.md) for details.

### OpenTelemetry#baggage

```scala
def baggage(logAnnotated: Boolean = false): URLayer[ContextStorage, Baggage]
```

Creates a `Baggage` service. It needs only a `ContextStorage` because baggage lives in the context, not in a Java instance. When `logAnnotated` is `true`, the ZIO log annotations are propagated as baggage entries. See [Baggage](baggage.md) for the service API.

## Common Wirings

A typical application with automatic instrumentation provides the global instance and the JVM storage:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

val agentLayer: TaskLayer[Tracing] =
  ZLayer.make[Tracing](
    OpenTelemetry.global,
    OpenTelemetry.contextJVM,
    OpenTelemetry.tracing("com.example.app")
  )
```

An application that configures the SDK itself swaps those two for `OpenTelemetry.custom` and `OpenTelemetry.contextZIO`. For complete walkthroughs, see [Getting Started](../getting-started.md) and [How to Use the Java Agent](../how-to/use-the-java-agent.md).
