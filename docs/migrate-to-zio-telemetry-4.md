---
id: migrate-to-zio-telemetry-4
title: "Migrate to ZIO Telemetry 4.0"
description: "Update a ZIO Telemetry 3.x application to 4.0, covering new package names, layer constructors, the Tracer and Span API, baggage, context propagators, metrics, and logging."
---

In this guide, we walk through each breaking change between ZIO Telemetry 3.x and 4.0 and show how to update the code that uses it. For every change, we show the 3.x code, the 4.0 replacement, and the reason for the change when the source makes it visible. Most of the changes are in the OpenTelemetry module, so most of this guide covers it. OpenTracing and OpenCensus keep their 3.x API.

We recommend working through the sections in order. The package and layer changes come first, and the tracing and metrics changes depend on them.

## Overview

The table below lists the renamed names. Names not in the table keep their 3.x spelling.

| 3.x Name                                                  | 4.0 Name                                                       |
| --------------------------------------------------------- | -------------------------------------------------------------- |
| `zio.telemetry.opentelemetry.tracing.Tracing`             | `zio.telemetry.opentelemetry.core.trace.Tracer` and `Span`     |
| `zio.telemetry.opentelemetry.metrics.Meter`               | `zio.telemetry.opentelemetry.core.metrics.Meter`               |
| `zio.telemetry.opentelemetry.common.Attributes`           | `zio.telemetry.opentelemetry.core.common.Attributes`           |
| `OpenTelemetry.tracing`                                   | `OpenTelemetry.tracer`                                         |
| `OpenTelemetry.metrics`                                   | `OpenTelemetry.meter`                                          |
| `OpenTelemetry.logging`                                   | `OpenTelemetry.installLogger`                                  |
| `OpenTelemetry.zioMetrics`                                | `OpenTelemetry.enableZioMetrics`                               |
| `OpenTelemetry.contextZIO` and `OpenTelemetry.contextJVM` | removed, see [Context Storage Layers](#context-storage-layers) |

## Dependencies and Build

The Scala artifact names for the four published modules are unchanged: `zio-opentelemetry`, `zio-opentelemetry-zio-logging`, `zio-opentracing`, and `zio-opencensus`. Version 4.0 also publishes `zio-opentelemetry-testkit` for writing tests against the OpenTelemetry API.

The 4.0 line is built against `opentelemetry-api` 1.67.0, while the 3.x line uses 1.68.0. If your application pins the OpenTelemetry Java artifacts directly, align them with the version that your ZIO Telemetry release depends on.

## Packages

The OpenTelemetry module moved its types into a `core` sub-package. Tracing types live in `core.trace`, logging types in `core.logs`, and the other packages sit under `core` with their names unchanged. Update the imports first, because the later sections use the new names. The examples in this guide share the following imports:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.trace.Tracer
import zio.telemetry.opentelemetry.core.metrics.Meter
```

The 3.x imports below are not compiled here, because the 3.x library is not on the docs classpath. They look like this:

```scala
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.common.Attributes
import zio.telemetry.opentelemetry.context.ContextStorage
```

The 4.0 names for these imports are `zio.telemetry.opentelemetry.core.trace.Tracer` and `Span`, `zio.telemetry.opentelemetry.core.metrics.Meter`, and `zio.telemetry.opentelemetry.core.common.Attributes`. The setup block above imports the ones that the later examples use, and the remaining examples add the rest where they need them.

`ContextStorage` no longer appears in application code. Its 4.0 version lives in the `internal` package, and you reach it only through `OpenTelemetry.unsafe`, so the import is not needed.

## Layers

The layer constructors in `zio.telemetry.opentelemetry.OpenTelemetry` changed names, parameters, and inputs. Each constructor now produces a `core.OpenTelemetry` service, and the tracing and metrics layers take that service as their input.

### Global and No-Op Instances Take Parentheses

`OpenTelemetry.global` and `OpenTelemetry.noop` are methods in 4.0 with a `logAnnotated` parameter, so you call them with parentheses. Their result type is `core.OpenTelemetry`. In 3.x, `OpenTelemetry.global` was a value that had no parameter list. This code is not compiled here:

```scala
val layer: TaskLayer[api.OpenTelemetry] = OpenTelemetry.global
```

In 4.0, the same layer is a method call:

```scala mdoc:compile-only
val globalLayer: TaskLayer[core.OpenTelemetry] = OpenTelemetry.global()
```

`OpenTelemetry.custom` keeps its single-argument form, and it gains optional parameters for the context propagator and the log annotation flag. Calls that pass only the scoped effect continue to compile.

### Tracer and Meter Layers Replace the Tracing and Metrics Layers

`OpenTelemetry.tracer` and `OpenTelemetry.meter` replace `OpenTelemetry.tracing` and `OpenTelemetry.metrics`. They take a `core.OpenTelemetry` as input and do not require a `ContextStorage`. The `OpenTelemetry.tracer` layer also accepts a `statusMapper` parameter, and the `OpenTelemetry.meter` layer no longer provides an `Instrument.Builder`.

In 3.x, the tracing and metrics layers also needed a context storage in their input. This code is not compiled here:

```scala
val layers: URLayer[api.OpenTelemetry & ContextStorage, Tracing & Meter & Instrument.Builder] =
  OpenTelemetry.tracing("com.example.orders") ++ OpenTelemetry.metrics("com.example.orders")
```

In 4.0, both layers take the `core.OpenTelemetry` service directly:

```scala mdoc:compile-only
val tracerAndMeter: RLayer[core.OpenTelemetry, Tracer & Meter] =
  OpenTelemetry.tracer("com.example.orders") ++ OpenTelemetry.meter("com.example.orders")
```

### Context Storage Layers

`OpenTelemetry.contextZIO` and `OpenTelemetry.contextJVM` are removed. The 4.0 constructors choose the storage themselves. `OpenTelemetry.global` uses the JVM thread-local storage that the OpenTelemetry Java agent also uses, and `OpenTelemetry.custom` and `OpenTelemetry.noop` use ZIO fiber references. Remove the `OpenTelemetry.contextZIO` and `OpenTelemetry.contextJVM` layers from your `ZLayer` graph.

### Baggage Layer Becomes a Member

`OpenTelemetry.baggage` was a layer in 3.x. In 4.0, `core.OpenTelemetry#baggage` is a member of the service, so the program reads it from the service. The section on [Baggage](#baggage) shows the new call.

### Logging and Metrics Layers

`OpenTelemetry.logging` became `OpenTelemetry.installLogger`. It installs ZIO's logger into OpenTelemetry Logs and takes the same scope name and level. Version 4.0 also adds `OpenTelemetry.zioLogger`, which returns a `ZLogger` that you can attach to a single effect with `ZIO.withLogger`.

`OpenTelemetry.zioMetrics` became `OpenTelemetry.enableZioMetrics`. It takes an instrumentation scope name, an optional version, and an optional schema URL. It produces `Unit` and enables the ZIO runtime metrics through the `core.OpenTelemetry` service.

## Tracing

The `Tracing` trait became `Tracer`, and the methods that operate on the current span moved to the new `Span` trait. The bodies of `Tracer#span`, `Tracer#root`, and `Tracer#continueSpan` now receive the span, so you no longer need to look it up.

### Span Bodies Receive the Span

In 3.x, the body of `Tracing#span` is a plain ZIO effect, and the span methods live on `Tracing`. This code is not compiled here:

```scala
def handle(tracing: Tracing): UIO[Unit] =
  tracing.span("handle-request") {
    tracing.setAttribute("http.method", "GET") *> tracing.addEvent("started")
  }
```

In 4.0, the body is a function from `Span` to a ZIO effect, so the same handler sets attributes and events on the span it runs in:

```scala mdoc:compile-only
def handle(tracer: Tracer): UIO[Unit] =
  tracer.span("handle-request") { span =>
    span.setAttribute("http.method", "GET") *> span.addEvent("started")
  }
```

### Root Spans Default to the Server Kind

`Tracer#root` now defaults to `SpanKind.SERVER`. In 3.x, the default was `SpanKind.INTERNAL`. To keep the 3.x default of `SpanKind.INTERNAL` for internal work, pass the kind explicitly:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind

def runJob(tracer: Tracer): UIO[Unit] =
  tracer.root("nightly-job", spanKind = SpanKind.INTERNAL) { span =>
    span.addEvent("job started")
  }
```

### Scoped and Unsafe Methods Are Renamed

Several methods changed names so that the name says what they do. The scoped-effect methods now use the `unmanaged` prefix, because they do not end the span when the effect finishes.

| 3.x Method               | 4.0 Method                    |
| ------------------------ | ----------------------------- |
| `inSpan(span, name)(zio)` | `continueSpan(span, name)(f)` |
| `scopedEffect`           | `unmanagedScope`              |
| `scopedEffectFromFuture` | `unmanagedScopeFuture`        |
| `scopedEffectTotal`      | `unmanagedScopeTotal`         |
| `spanUnsafe`             | `spanUnmanaged`               |

`spanScoped` keeps its name, and in 4.0 it returns a `ZIO[Scope, Nothing, Span]`, so the span ends when the scope closes. The 3.x `spanUnsafe` method returns a tuple of the span and a finalizer. Its 4.0 replacement, `spanUnmanaged`, returns the `Span` alone.

### Span Methods Moved to Span

Methods that act on a single span moved from `Tracing` to `Span`. Their signatures are unchanged, so you call them on the span that the body receives.

| 3.x Method                                           | 4.0 Method                                     |
| ---------------------------------------------------- | ---------------------------------------------- |
| `Tracing#addEvent`, `Tracing#addEventWithAttributes` | `Span#addEvent`, `Span#addEventWithAttributes` |
| `Tracing#setAttribute` overloads                     | `Span#setAttribute` overloads                  |

Version 4.0 also adds `Span#setAllAttributes`, `Span#setStatus`, `Span#recordException`, and `Span#end`.

### Context Injection and Extraction Are Replaced

`Tracing#injectSpan`, `Tracing#extractSpan`, and `Tracing#extractSpanUnsafe` are removed. Use `OpenTelemetry#propagate` to write the current span context into an outgoing carrier, and `OpenTelemetry#continue` to read the span context from an incoming carrier and run the effect inside it. Use `OpenTelemetry#continue` in place of the `Tracing#extractSpan` aspect.

The 3.x methods that read the current context without an effect, such as `getCurrentSpanUnsafe`, are also removed. This guide does not show a direct replacement for them, because the 4.0 source does not document one.

## Baggage

Baggage operations now take the effect that the value applies to, the same way `Tracer#span` takes its body. In 3.x, `Baggage#set` returns a `UIO[Unit]`, and the value stays set until it is removed. In 4.0, `Baggage#set` takes the effect and applies the value only inside that effect.

In 3.x, `Baggage#set` returns a `UIO[Unit]`, which you sequence with the rest of the program. This code is not compiled here:

```scala
def tag(baggage: Baggage): UIO[Option[String]] =
  baggage.set("tenant", "acme") *> baggage.get("tenant")
```

In 4.0, `Baggage#set` takes the effect that the value applies to:

```scala mdoc:compile-only
def tag(openTelemetry: core.OpenTelemetry): UIO[Option[String]] =
  openTelemetry.baggage.set("tenant", "acme") {
    openTelemetry.baggage.get("tenant")
  }
```

The `remove` and `setWithMetadata` methods follow the same pattern. The 3.x methods `extract`, `inject`, and `getCurrentBaggageUnsafe` are removed. Use the propagators described in the next section to carry baggage across process boundaries.

## Context Propagators

`BaggagePropagator` and `TraceContextPropagator` are replaced by a single `ContextPropagator` trait in `core.context`. Its companion object provides the W3C propagators, a combined propagator, and the default, which combines trace context and baggage.

In 3.x, each propagator had its own type, and both were referenced by name. This code is not compiled here:

```scala
val propagators = List(TraceContextPropagator.default, BaggagePropagator.default)
```

In 4.0, the propagators are values of a single type:

```scala mdoc:compile-only
import zio.telemetry.opentelemetry.core.context.ContextPropagator

val propagator: ContextPropagator =
  ContextPropagator.combined(ContextPropagator.w3cTraceContext, ContextPropagator.w3cBaggage)
```

The `OpenTelemetry.custom` layer takes the propagator as its first parameter, so you can pass it there instead of wiring it separately.

## Metrics

The metrics methods on `Meter` changed their return types. In 3.x, `counter`, `upDownCounter`, and `histogram` return a `UIO` that produces the instrument. In 4.0 they return the instrument directly, because creating an instrument does not perform an effect.

In 3.x, creating a counter is an effect, so the code binds it in a for-comprehension. This code is not compiled here:

```scala
val program: UIO[Unit] =
  for {
    requests <- meter.counter("requests")
    _        <- requests.inc()
  } yield ()
```

In 4.0, the instrument is a plain value:

```scala mdoc:compile-only
def countRequests(meter: Meter): UIO[Unit] = {
  val requests = meter.counter("requests")
  requests.inc()
}
```

Version 4.0 adds a `gauge` constructor that returns a `Gauge[Double]`, which records the latest value with `Gauge#set`. The [Record Metrics with OpenTelemetry](record-metrics-with-opentelemetry.md) guide shows every instrument with a complete example.

`Attributes.fromList` now takes a `List[Attribute[?]]`, so it accepts attributes of different types in one list. A list of a single type still compiles.

## StatusMapper

`StatusMapper.both` now takes the success mapper first and the failure mapper second. In 3.x, the failure mapper came first. This code is not compiled here:

```scala
StatusMapper.both(failureMapper, successMapper)
```

In 4.0, the success mapper comes first in `StatusMapper.both`:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.StatusCode
import zio.telemetry.opentelemetry.core.trace.StatusMapper

val successMapper = StatusMapper.successNoDescription[String](_ => StatusCode.OK)
val failureMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR)

val statusMapper = StatusMapper.both(successMapper, failureMapper)
```

The 4.0 failure mappers receive the ZIO `Cause` and read the typed failure from it with `Cause#failureOption`, so a defect does not reach the failure mapper. Review any custom failure mapper that relied on matching defects.

## Logging

The logging module follows the same rename as the layers. `ZioLogging.logFormats` now takes a `core.OpenTelemetry` service in place of a `ContextStorage`, so you provide the same service that the rest of your application uses.

In 4.0, the log formats layer takes the OpenTelemetry service:

```scala mdoc:compile-only
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}

val logFormats: URLayer[core.OpenTelemetry, LogFormats] = ZioLogging.logFormats
```

## OpenTracing and OpenCensus

The OpenTracing and OpenCensus modules keep their 3.x packages and their public API, so you do not need to change code that uses them when you move to 4.0. Their internal implementation changed, so an upgrade still requires running your test suite.

## Going Further

- The [OpenTelemetry](opentelemetry.md) page describes the layers and the tracing API in detail.
- The [Record Metrics with OpenTelemetry](record-metrics-with-opentelemetry.md) guide shows the metrics API end to end.
- The [OpenTelemetry ZIO Logging](opentelemetry-zio-logging.md) page describes the logging integration.
