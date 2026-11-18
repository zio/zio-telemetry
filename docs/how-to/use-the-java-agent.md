---
id: use-the-java-agent
title: "How to Use the Java Agent"
sidebar_label: "Use the Java Agent"
description: "Run a ZIO application under the OpenTelemetry Java agent and combine automatic instrumentation with manual ZIO Telemetry spans."
keywords:
  - "Java agent"
  - "automatic instrumentation"
  - "OpenTelemetry.global"
  - "OpenTelemetry.contextJVM"
---

In this guide, we will run a ZIO application under the OpenTelemetry Java agent so that library calls, such as HTTP servers and clients, are traced automatically, and our own spans created with ZIO Telemetry join the same traces. The key step is to let ZIO Telemetry use the agent's global SDK and its thread-local context instead of creating its own.

## The Problem

The agent instruments supported Java libraries by rewriting their bytecode, and it tracks the current span in a thread-local `Context`. ZIO fibers hop between threads, and a ZIO Telemetry setup created with `OpenTelemetry.contextZIO` keeps the current span in a `FiberRef` that the agent knows nothing about. The result is two unrelated trees of spans: the agent's spans for the incoming request and our manual spans, each in its own trace.

To get a single trace, ZIO Telemetry has to read and write the same context the agent uses, and it has to use the tracer that the agent configured.

## Prerequisites

The application depends on ZIO Telemetry only. The SDK and the exporters come from the agent:

```sbt
// ZIO Telemetry for OpenTelemetry
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

The agent supports ZIO since [version 1.25.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.25.0). We download the agent JAR, here with Coursier:

```bash
OTEL_AGENT_PATH=$(cs fetch --classpath "io.opentelemetry.javaagent:opentelemetry-javaagent:latest.release")
```

We assume you are familiar with [how context is stored](../concepts/context-and-fibers.md).

## Using the Global SDK and the JVM Context

We replace the two layers that differ from a manual setup. `OpenTelemetry.global` returns the `GlobalOpenTelemetry` instance that the agent registers, and `OpenTelemetry.contextJVM` stores the context in OpenTelemetry's own thread-local storage. The tracing layer and everything else stay the same:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

val health: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("health-check") {
      tracing.setAttribute("zio", "telemetry") *> ZIO.logInfo("health check finished")
    }
  }
```

Providing the layers is where the agent setup differs from a self-configured SDK:

```scala mdoc:compile-only
val app: Task[Unit] =
  health.provide(
    OpenTelemetry.global,
    OpenTelemetry.tracing("com.example.health"),
    OpenTelemetry.contextJVM
  )
```

:::warning
Use `OpenTelemetry.contextJVM` only when the agent is attached. It relies on the agent to carry the thread-local context across ZIO's thread switches. Without the agent, use `OpenTelemetry.contextZIO` together with `OpenTelemetry.custom`.
:::

## Starting the Application with the Agent

We attach the agent with the `-javaagent` JVM option and configure it with `otel.*` system properties, or the matching environment variables. This command runs the `opentelemetryInstrumentationExample` server from this repository with the sampler set to `always_on` and traces exported over OTLP:

```bash
sbt -J-javaagent:$OTEL_AGENT_PATH \
    -J-Dotel.service.name=example-server \
    -J-Dotel.traces.sampler=always_on \
    -J-Dotel.traces.exporter=otlp \
    -J-Dotel.metrics.exporter=none \
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ServerApp"
```

The agent exports to `http://localhost:4317` by default. The [automatic instrumentation example](../opentelemetry-instrumentation-example.md) shows the full setup with Jaeger and Seq, including how logs are forwarded.

## Checking the Result

When a request reaches the server, the trace contains the agent's server span for the HTTP request and, as its child, our `health-check` span with the attribute and the log record. If you see two separate traces, check that the agent is attached and that the program uses `OpenTelemetry.contextJVM` rather than `OpenTelemetry.contextZIO`.

## Going Further

- [How to Export Signals with OTLP](export-signals-with-otlp.md) shows the alternative where the application configures the SDK itself.
- [How to Wrap Blocking Code in a Span](wrap-blocking-code-in-a-span.md) explains `Tracing#scopedEffect`, which lets non-ZIO code see the current span through the thread-local context.
- [Context and Fibers](../concepts/context-and-fibers.md) explains why the two context storages exist.
