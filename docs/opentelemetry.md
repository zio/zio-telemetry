---
id: opentelemetry
title: "OpenTelemetry"
---

The OpenTelemetry module of ZIO Telemetry, published as `zio-opentelemetry`, is an idiomatic ZIO interface to the [OpenTelemetry](https://opentelemetry.io) Java API. It lets you create spans, record metrics, emit log records and carry baggage from ZIO code, with the ZIO effect acting as the scope of a span. OpenTelemetry is the successor of OpenTracing and OpenCensus and is the module to use for new projects; see [Choosing a Module](concepts/choosing-a-module.md) for how it compares to the legacy modules.

The module depends only on `opentelemetry-api`. You decide which SDK and exporters your application uses and hand the configured instance to the library through a layer.

Some of the key features:

- **ZIO native.** The API builds on ZIO's resource management, dependency injection, logging, metrics and `ZIOAspect`.
- **Runtime interoperability.** You do not manipulate OpenTelemetry's `Context` yourself; the module keeps it in a `ContextStorage` that follows fibers.
- **Signal correlation.** Spans, metrics, log records and baggage created inside a span are automatically correlated with it.
- **ZIO integration.** Log annotations can flow into span attributes, metric attributes and baggage, and ZIO runtime metrics can be exported as OpenTelemetry metrics.

## Installation

To use the module, add the following dependency to your `build.sbt`:

```scala
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

The module only brings the OpenTelemetry API, so your application also needs an SDK and the exporters for your backend. For example:

```scala
libraryDependencies ++= Seq(
  // Implementation of the OpenTelemetry API
  "io.opentelemetry" % "opentelemetry-sdk" % "<opentelemetry-java-version>",
  // Exports signals to an OTLP collector over gRPC
  "io.opentelemetry" % "opentelemetry-exporter-otlp" % "<opentelemetry-java-version>"
)
```

The complete list of Java artifacts is available in the [OpenTelemetry Java repository](https://github.com/open-telemetry/opentelemetry-java#releases). If you use the Java agent instead of configuring an SDK in code, you need neither of them; see [How to Use the Java Agent](how-to/use-the-java-agent.md).

## What the Module Contains

Everything lives under the `zio.telemetry.opentelemetry` package:

- `OpenTelemetry` is the entry point, an object of layers that builds all the services below.
- `tracing.Tracing` creates spans and sets their attributes, events and status.
- `metrics.Meter` creates counters, up-down counters, histograms, gauges and their observable variants.
- `baggage.Baggage` reads and writes baggage entries.
- `logging.Logging` turns `ZIO.log*` calls into OpenTelemetry log records. It is installed with the `OpenTelemetry.logging` layer and has no service you call directly.
- `context.ContextStorage` stores the current OpenTelemetry context, and `context.IncomingContextCarrier` and `context.OutgoingContextCarrier` move it across process boundaries.
- `common.Attributes` and `common.Attribute` build attribute sets.

## Wiring at a Glance

Every service is built by a layer of the `OpenTelemetry` object, and every layer needs the Java `OpenTelemetry` instance and a `ContextStorage`. A complete wiring that discards all signals, which is convenient for a first compile, is:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing](_.span("hello")(ZIO.unit))

val runnable: UIO[Unit] =
  program.provide(
    OpenTelemetry.noop,
    OpenTelemetry.contextZIO,
    OpenTelemetry.tracing("com.example.app")
  )
```

Replace `OpenTelemetry.noop` with `OpenTelemetry.custom` or `OpenTelemetry.global` to export real telemetry. [Getting Started](getting-started.md) walks through a working setup end to end.

## Usage with OpenTelemetry Automatic Instrumentation

When the OpenTelemetry Java agent instruments the application, `Tracing` must share the agent's context and tracer provider, which means providing `OpenTelemetry.contextJVM` and `OpenTelemetry.global` instead of `OpenTelemetry.contextZIO` and `OpenTelemetry.custom`. [How to Use the Java Agent](how-to/use-the-java-agent.md) walks through the setup.

## Reference Pages

The reference pages describe the API by purpose.

### Setup and Context

- [OpenTelemetry Layers](reference/opentelemetry-layers.md) lists every layer of the `OpenTelemetry` object, what it requires and provides.
- [ContextStorage](reference/context-storage.md) describes the context store and the trade-off between the `FiberRef` and the `ThreadLocal` implementations.
- [Context Propagation](reference/context-propagation.md) covers carriers and propagators for passing context between processes.

### Signals

- [Tracing](reference/tracing.md) documents the `Tracing` service and its aspects.
- [Status Mapper](reference/status-mapper.md) documents how a span's final status is derived from the result of its effect.
- [Attributes](reference/attributes.md) documents the attribute builders shared by all signals.
- [Metrics](reference/metrics.md) documents the `Meter` service and its instruments.
- [Logging](reference/logging.md) documents the log signal.
- [Baggage](reference/baggage.md) documents the `Baggage` service.

## How-To Guides

When you have a concrete goal, these guides give the steps:

- [How to Export Signals with OTLP](how-to/export-signals-with-otlp.md)
- [How to Use the Java Agent](how-to/use-the-java-agent.md)
- [How to Propagate Context over HTTP](how-to/propagate-context-over-http.md)
- [How to Record Metrics](how-to/record-metrics.md)
- [How to Customize Span Status](how-to/customize-span-status.md)
- [How to Test Traced Code](how-to/test-traced-code.md)
- [How to Correlate Logs with Traces](how-to/correlate-logs-with-traces.md)
- [How to Wrap Blocking Code in a Span](how-to/wrap-blocking-code-in-a-span.md)
- [How to Migrate from OpenTracing or OpenCensus](how-to/migrate-from-opentracing-or-opencensus.md)

For a longer walk through a realistic service, see the [tutorial](tutorials/instrument-an-order-service.md), the [OpenTelemetry example](opentelemetry-example.md) and the [instrumentation example](opentelemetry-instrumentation-example.md). If you want logs of the ZIO Logging library to carry trace information, see [OpenTelemetry ZIO Logging](opentelemetry-zio-logging.md).

The Scala CLI versions of the examples that previously lived on this page are in the `scala-cli/opentelemetry/` directory of the repository.
