---
id: choosing-a-module
title: "Choosing a Module"
---

ZIO Telemetry ships clients for three observability APIs: OpenTelemetry, OpenTracing and OpenCensus. They are separate artifacts with separate APIs, and you should pick one.

## The Upstream Situation

OpenTracing and OpenCensus are both deprecated upstream. Their projects merged into OpenTelemetry, which is now the single vendor-neutral standard for traces, metrics and logs. The upstream libraries receive no new features, and the instrumentation ecosystem has moved on to OpenTelemetry.

## When to Use Which

Use [OpenTelemetry](../opentelemetry.md) for any new project. It is the only module that covers traces, metrics, logs and baggage, it is the only one that works with the OpenTelemetry Java agent, and it is the one that receives new features.

Use [OpenTracing](../opentracing.md) or [OpenCensus](../opencensus.md) only when you maintain an application that already depends on them, for example because your existing tracer or backend integration is built on their APIs. Both modules remain available, but they cover tracing only, and the OpenTracing API is not further developed. The how-to guide [How to Migrate from OpenTracing or OpenCensus](../how-to/migrate-from-opentracing-or-opencensus.md) describes the move.

## Correlating Logs

If you use ZIO Logging, the `zio-opentelemetry-zio-logging` artifact adds the `LogFormats` service that provides trace ID and span ID log formats. It builds on the OpenTelemetry module and uses the same `ContextStorage`, so your text logs carry the identifiers of the active span. See [OpenTelemetry ZIO Logging](../opentelemetry-zio-logging.md).

Add it next to `zio-opentelemetry` only when you want these identifiers in ZIO Logging output. If you export logs as OpenTelemetry log records with `OpenTelemetry.logging`, you do not need it. The artifact coordinates are in the [Installation](../index.md#installation) section.
