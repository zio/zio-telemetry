---
id: index
title: "Introduction to ZIO Telemetry"
sidebar_label: "Introduction"
---

[ZIO Telemetry](https://github.com/zio/zio-telemetry) is a purely functional, type-safe client for distributed tracing, metrics, logs, and baggage in [ZIO](https://zio.dev) applications. Its main module wraps [OpenTelemetry](https://opentelemetry.io/); older modules wrap [OpenTracing](https://opentracing.io/) and [OpenCensus](https://opencensus.io/), both deprecated upstream.

@PROJECT_BADGES@

In a monolith, a request starts, passes through a few components, and finishes in one place. In a distributed system, the story of a request is spread across many services, and no single log tells it. Distributed tracing connects those pieces: every service records spans that share a trace, and the trace context travels between services with the request. ZIO Telemetry lets a ZIO effect be the scope of a span, correlates metrics and logs with the surrounding span, and propagates context across service boundaries.

## Installation

The OpenTelemetry module is all most applications need. It is published for the JVM only, for Scala 2.12, 2.13, and 3:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenTelemetry (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
)
```

The module depends only on the OpenTelemetry API. To produce and export signals, an application also needs the OpenTelemetry SDK and an exporter; see [Getting Started](getting-started.md) for a working setup.

## Modules

The remaining modules are optional.

### Integrations

To add trace and span ids to [ZIO Logging](https://zio.dev/zio-logging/) output, use the logging integration:

```scala
libraryDependencies ++= Seq(
  // Span and trace id log formats for ZIO Logging (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opentelemetry-zio-logging" % "@VERSION@"
)
```

See [OpenTelemetry ZIO Logging](opentelemetry-zio-logging.md).

### Legacy Modules

OpenTracing is archived and OpenCensus is deprecated upstream, and new applications should use the OpenTelemetry module. These modules remain for existing code:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenTracing (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opentracing" % "@VERSION@",

  // ZIO Telemetry for OpenCensus (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opencensus" % "@VERSION@"
)
```

See [OpenTracing](opentracing.md) and [OpenCensus](opencensus.md).
