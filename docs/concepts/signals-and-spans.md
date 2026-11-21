---
id: signals-and-spans
title: "Signals and Spans"
---

Observability data in OpenTelemetry comes in several kinds, called signals. ZIO Telemetry exposes four of them through the `OpenTelemetry` entry point, and all share one idea: the data is tied to the operation that was running when it was recorded.

## The Signals

A trace describes the path of one request through your system as a tree of spans. A metric is a numeric measurement aggregated over time, such as a request counter or a latency histogram. A log is a timestamped record of something that happened. Baggage is not telemetry data itself. It is a set of key-value pairs that travels along with the request so that every service involved can read it.

In the library, each signal has its own layer. `OpenTelemetry.tracing` provides `Tracing`, `OpenTelemetry.metrics` provides `Meter`, `OpenTelemetry.logging` forwards `ZIO.log*` calls as OpenTelemetry log records, and `OpenTelemetry.baggage` provides `Baggage`. You only add the layers for the signals you use.

## What a Span Is

A span represents one unit of work, such as handling an HTTP request or querying a database. It has a name, a start time and an end time, a kind (internal, server, client, producer or consumer), a status, and optionally attributes, events and links to other spans.

Attributes are key-value pairs that describe the work, such as a customer ID. Events are timestamped notes inside the span, such as "cache miss". A span belongs to a trace, identified by a trace ID, and has its own span ID and, unless it is a root span, a parent span ID. These three IDs let a backend rebuild the tree.

## A ZIO Effect Is the Span Scope

In ZIO Telemetry we never start and end spans by hand. Instead, we wrap an effect, and the span lives exactly as long as that effect. `Tracing#span` creates a child of the current span, runs the effect, and ends the span when the effect completes, fails or is interrupted. `Tracing#root` does the same for a new trace.

Because the scope is the effect, spans nest the way effects nest, and a failure or an interruption always closes the span. The status of the span is derived from the effect outcome by a status mapper, which you can customize.

When the span scope should be a `Scope` rather than an effect, use `Tracing#spanScoped`, which ends the span when the surrounding scope closes.

## Correlation of Signals

The current span is part of the fiber context. When you write a log with `ZIO.log*` inside a span, the log record is created with the surrounding context, so it carries the trace and span IDs without extra work. In a backend, you can jump from a span to the logs recorded during it. Metrics share the same `ContextStorage` and instrumentation scope setup.

Log annotations can also be copied onto spans and baggage by passing `logAnnotated = true` to the respective layer. Correlating plain text logs is covered by the [OpenTelemetry ZIO Logging](../opentelemetry-zio-logging.md) integration.

For how to create these layers, see [OpenTelemetry](../opentelemetry.md).
