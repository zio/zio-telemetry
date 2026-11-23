---
id: export-signals-with-otlp
title: "How to Export Signals with OTLP"
sidebar_label: "Export Signals with OTLP"
description: "Configure the OpenTelemetry SDK so ZIO Telemetry traces, metrics and logs are exported to a collector or backend over OTLP."
keywords:
  - "OTLP"
  - "OpenTelemetry SDK"
  - "span exporter"
  - "OpenTelemetry.custom"
---

In this guide, we will wire the OpenTelemetry Java SDK into ZIO Telemetry so that traces, metrics and logs leave your application over OTLP, the OpenTelemetry protocol. We build one `OpenTelemetry` layer that takes three exporters as parameters, run it first with exporters that log OTLP JSON through `java.util.logging`, and then swap in network exporters that talk to a collector.

## The Problem

ZIO Telemetry creates spans, records measurements and forwards log records, but it does not decide where they go. That is the job of the OpenTelemetry SDK: it owns the tracer, meter and logger providers, the processors that batch data, and the exporters that ship it. If we skip this setup, the library has nothing to hand its data to, and nothing is exported.

The SDK is a Java library with mutable builders and resources that must be closed in the right order. We want to build it once, as a `ZLayer`, so ZIO closes it and flushes pending data on shutdown.

## Prerequisites

We need ZIO Telemetry and the SDK itself. The `opentelemetry-exporter-logging-otlp` artifact logs OTLP JSON through `java.util.logging`, which is convenient while developing:

```sbt
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenTelemetry
  "dev.zio" %% "zio-opentelemetry" % "@VERSION@",
  // The OpenTelemetry SDK: providers, processors and readers
  "io.opentelemetry" % "opentelemetry-sdk" % "1.68.0",
  // Exporters that log OTLP JSON through java.util.logging
  "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % "1.68.0"
)
```

We use these imports throughout the guide:

```scala mdoc:silent
import io.opentelemetry.api
import io.opentelemetry.exporter.logging.otlp.{
  OtlpJsonLoggingLogRecordExporter,
  OtlpJsonLoggingMetricExporter,
  OtlpJsonLoggingSpanExporter
}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.{BatchLogRecordProcessor, LogRecordExporter}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.{MetricExporter, PeriodicMetricReader}
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{BatchSpanProcessor, SpanExporter}
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.tracing.Tracing
```

## Building the SDK Layer

We write one function that receives the three exporters and returns a `TaskLayer[api.OpenTelemetry]`. Each signal gets a provider with a batching processor, and all providers share one `Resource` that carries the `service.name` attribute so the backend can tell services apart. `OpenTelemetry.custom` takes a scoped effect that returns the SDK, so the layer closes the SDK when the application ends, and closing the SDK shuts down the providers and flushes batched data:

```scala mdoc:silent
def sdkLayer(
  serviceName: String,
  spanExporter: SpanExporter,
  metricExporter: MetricExporter,
  logExporter: LogRecordExporter
): TaskLayer[api.OpenTelemetry] =
  OpenTelemetry.custom(
    for {
      resource <- ZIO.succeed(Resource.getDefault().toBuilder().put("service.name", serviceName).build())
      tracer   <- ZIO.succeed(
                    SdkTracerProvider
                      .builder()
                      .setResource(resource)
                      .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                      .build()
                  )
      meter    <- ZIO.succeed(
                    SdkMeterProvider
                      .builder()
                      .setResource(resource)
                      .registerMetricReader(PeriodicMetricReader.builder(metricExporter).build())
                      .build()
                  )
      logger   <- ZIO.succeed(
                    SdkLoggerProvider
                      .builder()
                      .setResource(resource)
                      .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                      .build()
                  )
      sdk      <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OpenTelemetrySdk
                        .builder()
                        .setTracerProvider(tracer)
                        .setMeterProvider(meter)
                        .setLoggerProvider(logger)
                        .build()
                    )
                  )
    } yield sdk
  )
```

## Starting with Console Logging

Before involving a network, we plug in the exporters that log OTLP JSON. This is the quickest way to see that spans, metrics and log records are produced:

```scala mdoc:silent
def stdoutSdk: TaskLayer[api.OpenTelemetry] =
  sdkLayer(
    "my-service",
    OtlpJsonLoggingSpanExporter.create(),
    OtlpJsonLoggingMetricExporter.create(),
    OtlpJsonLoggingLogRecordExporter.create()
  )
```

## Switching to a Collector

The OTLP network exporters live in a separate artifact. We add it to the build:

```sbt
// OTLP exporters over gRPC and HTTP/protobuf
libraryDependencies += "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.68.0"
```

The exporters are not part of the documentation build, so the next snippet is shown without compilation. It builds the gRPC variants and passes them to the same `sdkLayer` function. The gRPC exporters default to `http://localhost:4317`, the port a local collector or Jaeger listens on:

```scala
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter

val otlpSdk: TaskLayer[api.OpenTelemetry] =
  sdkLayer(
    "my-service",
    OtlpGrpcSpanExporter.builder().setEndpoint("http://localhost:4317").build(),
    OtlpGrpcMetricExporter.builder().setEndpoint("http://localhost:4317").build(),
    OtlpGrpcLogRecordExporter.builder().setEndpoint("http://localhost:4317").build()
  )
```

If your backend speaks OTLP over HTTP instead, use `OtlpHttpSpanExporter`, `OtlpHttpMetricExporter` and `OtlpHttpLogRecordExporter` from the `io.opentelemetry.exporter.otlp.http.*` packages. With the HTTP exporters, `setEndpoint` expects the full signal path, such as `http://localhost:5341/ingest/otlp/v1/logs` for logs in the [Seq example](../opentelemetry-example.md).

:::tip
You can also configure endpoints without code. The SDK's autoconfigure module reads `OTEL_EXPORTER_OTLP_ENDPOINT` and related environment variables, which is how the [Java agent](use-the-java-agent.md) is configured.
:::

## Wiring the Signals into Your Program

The SDK layer is the base. On top of it we add one ZIO Telemetry layer per signal, and a `ContextStorage` layer that all of them share. `OpenTelemetry.contextZIO` keeps the current span in a `FiberRef`, which is the right choice when you are not running under the Java agent. We give each layer the same instrumentation scope name, which identifies your application code in the exported data:

```scala mdoc:compile-only
val program: ZIO[Tracing & Meter, Nothing, Unit] =
  ZIO.unit

val app: Task[Unit] =
  program.provide(
    stdoutSdk,
    OpenTelemetry.tracing("com.example.my-service"),
    OpenTelemetry.metrics("com.example.my-service"),
    OpenTelemetry.logging("com.example.my-service"),
    OpenTelemetry.contextZIO
  )
```

`OpenTelemetry.logging` produces no service of its own. It installs a ZIO logger that turns `ZIO.log*` calls into OpenTelemetry log records, so it only has to be present in the layer graph.

## Going Further

- The [OpenTelemetry example](../opentelemetry-example.md) switches between console logging, Jaeger, Fluent Bit and Seq by swapping the provider functions.
- To attach to a process that already has the OpenTelemetry Java agent, follow [How to Use the Java Agent](use-the-java-agent.md).
- Trace and log correlation across the exported data is covered in [How to Correlate Logs with Traces](correlate-logs-with-traces.md).
