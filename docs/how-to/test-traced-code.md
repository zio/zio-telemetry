---
id: test-traced-code
title: "How to Test Traced Code"
description: "Assert on the spans and log records your ZIO code produces by using the in-memory exporters from the OpenTelemetry SDK testing module."
keywords:
  - "OpenTelemetry"
  - "testing"
  - "InMemorySpanExporter"
  - "Tracing"
---

In this guide, we will test code that creates spans without starting a collector or any other external process. By the end, we have a small test layer that provides a real `Tracing` service backed by an in-memory exporter, and a way to read the finished spans back and assert on their names, parents, attributes and status.

We build the layer first, run a traced program against it, and then assert on successful and failed spans, and on log records.

## The Problem

Tracing code is easy to break silently. A renamed span, a missing attribute, or a span that no longer ends in an error status does not fail any ordinary test, because the traced effect still returns the same value. Tracing with the no-op layer, `OpenTelemetry.noop`, hides the problem completely since it discards everything.

Sending the signals to a real collector in tests makes them slow and flaky. What we want is the real SDK, so the spans are created exactly as in production, but with an exporter that keeps the finished spans in memory where the test can read them.

## Prerequisites

The in-memory exporters are shipped in the `opentelemetry-sdk-testing` artifact of OpenTelemetry. We add it, together with the SDK and the ZIO Telemetry module, to the project:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry OpenTelemetry module
  "dev.zio"           %% "zio-opentelemetry"         % "@VERSION@",
  // OpenTelemetry SDK, which provides SdkTracerProvider
  "io.opentelemetry"   % "opentelemetry-sdk"         % "1.68.0",
  // In-memory exporters for tests
  "io.opentelemetry"   % "opentelemetry-sdk-testing" % "1.68.0" % Test
)
```

All the examples below share the following imports:

```scala mdoc:silent
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

import scala.jdk.CollectionConverters._
```

## Building a Test Layer

The layer has three parts. An `InMemorySpanExporter` collects the finished spans. A `Tracer` is created from an `SdkTracerProvider` whose span processor feeds that exporter. Finally `Tracing.live` turns the `Tracer` into a `Tracing` service.

We use `SimpleSpanProcessor` because it exports each span synchronously when it ends. The batching processor used in production exports on a timer, which would make tests wait or miss spans.

First, the exporter and the tracer:

```scala mdoc:silent
val exporterLayer: ULayer[InMemorySpanExporter] =
  ZLayer.succeed(InMemorySpanExporter.create())

val tracerLayer: URLayer[InMemorySpanExporter, Tracer] =
  ZLayer.fromZIO(
    ZIO.serviceWith[InMemorySpanExporter] { exporter =>
      SdkTracerProvider
        .builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
        .get("test")
    }
  )
```

Then we assemble the final layer. It outputs both `Tracing` for the code under test and the exporter for the assertions, and both see the same exporter instance because `ZLayer.make` shares a layer that several others depend on:

```scala mdoc:silent
val testLayer: ULayer[Tracing & InMemorySpanExporter] =
  ZLayer.make[Tracing & InMemorySpanExporter](
    Tracing.live(),
    tracerLayer,
    exporterLayer,
    OpenTelemetry.contextZIO
  )
```

`OpenTelemetry.contextZIO` stores the current span in a fiber ref, which is the right choice for tests that do not use the Java agent.

## Reading the Finished Spans

An `InMemorySpanExporter` returns its spans through `getFinishedSpanItems`, which is a Java list of `SpanData`. A small helper converts it to a Scala list:

```scala mdoc:silent
val finishedSpans: URIO[InMemorySpanExporter, List[SpanData]] =
  ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)
```

Spans appear in the order in which they end, so a child span comes before its parent.

:::tip
The exporter keeps spans until you call `InMemorySpanExporter#reset`. Because `testLayer` creates a fresh exporter for every test that provides it, you only need `reset` when several tests share one exporter.
:::

## Asserting on Spans

Now we need something to test. The following checkout flow creates a root span, a child span and one attribute:

```scala mdoc:silent
def checkout(orderId: String): URIO[Tracing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    import tracing.aspects._

    val chargeCard: UIO[Unit] =
      tracing.setAttribute("order.id", orderId) @@ span("charge-card")

    chargeCard @@ root("checkout")
  }
```

We run it with the test layer and project every finished span to the fields we care about. We use `Runtime.default.unsafe.run` here only to show the result in this page; in a real test the effect runs in the test framework:

```scala mdoc
Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe
    .run(
      (checkout("42") *> finishedSpans)
        .map(_.map(s => (s.getName, s.getParentSpanContext.isValid, s.getStatus.getStatusCode)))
        .provideLayer(testLayer)
    )
    .getOrThrowFiberFailure()
}
```

The child `charge-card` span ends first and has a valid parent, while `checkout` is a root span with no parent. Other getters on `SpanData`, such as `getAttributes`, `getEvents`, `getKind` and `getParentSpanId`, cover everything else a test usually checks.

## Asserting on Failed Spans

Failures are recorded on the span in the same way. We run a failing effect inside a span and read its status back:

```scala mdoc
Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe
    .run(
      (for {
        tracing <- ZIO.service[Tracing]
        _       <- (ZIO.fail("card declined") @@ tracing.aspects.span("payment")).ignore
        spans   <- finishedSpans
      } yield spans.map(s => (s.getName, s.getStatus.getStatusCode, s.getStatus.getDescription.nonEmpty)))
        .provideLayer(testLayer)
    )
    .getOrThrowFiberFailure()
}
```

This is also the way to verify a custom `StatusMapper`. See [How to Customize Span Status](customize-span-status.md).

## Writing the Test with ZIO Test

In a real project we use ZIO Test and let it run the effect. This block is not compiled on this page because `zio-test` is not on the documentation classpath. Add `zio-test` and `zio-test-sbt` to the test dependencies, then write:

```scala
import zio.test._

object CheckoutSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    test("checkout creates a root span with one child") {
      for {
        _     <- checkout("42")
        spans <- finishedSpans
      } yield assertTrue(spans.map(_.getName) == List("charge-card", "checkout"))
    }.provide(testLayer)

}
```

## Asserting on Log Records

The same approach works for OpenTelemetry log records. `opentelemetry-sdk-testing` also provides `InMemoryLogRecordExporter`, which we connect to an `SdkLoggerProvider` through a `SimpleLogRecordProcessor`. `OpenTelemetry.logging` obtains the provider from an `OpenTelemetry` instance, so for a test we use `Logging.live` from `zio.telemetry.opentelemetry.logging`, which takes the `LoggerProvider` directly.

We build the layer as before. `Runtime.removeDefaultLoggers` keeps the console output out of the test, and `Logging.live` installs the OpenTelemetry logger for the lifetime of the layer:

```scala mdoc:compile-only
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.logging.Logging

val logExporterLayer: ULayer[InMemoryLogRecordExporter] =
  ZLayer.succeed(InMemoryLogRecordExporter.create())

val loggerProviderLayer: URLayer[InMemoryLogRecordExporter, LoggerProvider] =
  ZLayer.fromZIO(
    ZIO.serviceWith[InMemoryLogRecordExporter] { exporter =>
      SdkLoggerProvider
        .builder()
        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
        .build()
    }
  )

val logTestLayer: ULayer[InMemoryLogRecordExporter] =
  Runtime.removeDefaultLoggers >>>
    (ContextStorage.fiberRef ++ (logExporterLayer >+> loggerProviderLayer)) >>>
    (Logging.live("test") ++ ZLayer.environment[InMemoryLogRecordExporter])

val finishedLogRecords: URIO[InMemoryLogRecordExporter, List[LogRecordData]] =
  ZIO.serviceWith[InMemoryLogRecordExporter](_.getFinishedLogRecordItems.asScala.toList)
```

A test then logs with `ZIO.logInfo` and reads `finishedLogRecords`. Each `LogRecordData` exposes `getBodyValue`, `getSeverity`, `getAttributes` and `getSpanContext`; the last one holds the trace and span ids when the log call happens inside a span.

## Going Further

The same pattern covers metrics with `InMemoryMetricReader` from the same artifact. For how spans reach a real backend, see [How to Export Signals with OTLP](export-signals-with-otlp.md).
