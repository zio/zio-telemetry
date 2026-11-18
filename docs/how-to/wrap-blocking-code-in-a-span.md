---
id: wrap-blocking-code-in-a-span
title: "How to Wrap Blocking Code in a Span"
sidebar_label: "Wrap Blocking Code in a Span"
description: "Trace blocking and callback-based code with ZIO Telemetry, and make the current span visible to non-ZIO libraries."
keywords:
  - "blocking"
  - "Tracing#span"
  - "Tracing#scopedEffect"
  - "thread-local context"
---

In this guide, we will trace a piece of blocking code, such as a JDBC call or a call to a synchronous SDK, so that it appears as its own span in the trace. We start with the simple case where only our own code needs the span, and then handle the case where the blocking library itself reads the current OpenTelemetry span.

## The Problem

Blocking code does not return a `ZIO` value, so it cannot take part in a trace on its own. We wrap it in an effect, run it on ZIO's blocking thread pool, and we want a span that measures how long it took and shows up as a child of the surrounding request.

There is a second, subtler problem. OpenTelemetry keeps the current span in a thread-local `Context`. ZIO Telemetry, with `OpenTelemetry.contextZIO`, keeps it in a `FiberRef` instead. A Java library that calls `Span.current()` or that is instrumented by an OpenTelemetry library integration sees the thread-local value, which is empty unless we put the context there ourselves.

## Prerequisites

We need `zio-opentelemetry` and a `Tracing` service. See [How to Export Signals with OTLP](export-signals-with-otlp.md) for the setup:

```sbt
// ZIO Telemetry for OpenTelemetry
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

These are the imports for the guide:

```scala mdoc:silent
import io.opentelemetry.api.trace.{Span, SpanKind}
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.tracing.Tracing
```

## Putting a Span Around the Blocking Call

`Tracing#span` creates a child of the current span, runs the effect, and ends the span when the effect finishes. We combine it with `ZIO.attemptBlocking`, which shifts the work to the blocking pool. The span context travels with the fiber, so the shift does not break the trace, and any failure of the blocking code is recorded on the span.

For a database call, we mark the span `SpanKind.CLIENT` and give it an attribute:

```scala mdoc:silent
def runQuery(tracing: Tracing, sql: String): Task[Int] =
  tracing.span("db.query", SpanKind.CLIENT, Attributes(Attribute.string("db.statement", sql))) {
    ZIO.attemptBlocking {
      // blocking JDBC or SDK call goes here
      42
    }
  }
```

The span covers the time spent waiting for a free blocking thread and the time running the call. If you want to measure the call only, put the span inside `ZIO.blocking`.

## Making the Span Visible to Blocking Libraries

When the blocking code reads the thread-local context, we use `Tracing#scopedEffect`. It makes the fiber's current context the current context of the running thread for the duration of the given code, and closes the scope afterwards. The code inside can then call `Span.current()`, and libraries with OpenTelemetry integration attach their own child spans to our span.

We run it on the blocking pool with `ZIO.blocking`, which keeps the thread-local scope and the blocking work on the same thread:

```scala mdoc:silent
def runInstrumentedLibrary(tracing: Tracing): Task[String] =
  tracing.span("call-library") {
    ZIO.blocking {
      tracing.scopedEffect {
        // a blocking library that uses the thread-local OpenTelemetry context
        val current = Span.current().getSpanContext
        s"trace: ${current.getTraceId}"
      }
    }
  }
```

`Tracing#scopedEffect` returns a `Task`, so an exception thrown by the code becomes a failure. If the code cannot throw, use `Tracing#scopedEffectTotal`, which returns a `UIO`.

:::warning
The scope is open only while the passed code runs. If that code starts work on another thread, such as a callback or an executor, the context does not follow automatically, unless the OpenTelemetry Java agent is attached and instruments that executor.
:::

## Wrapping Future-Based Code

For code that returns a `scala.concurrent.Future`, `Tracing#scopedEffectFromFuture` plays the same role. It opens the scope while the `Future` is created, with the `ExecutionContext` supplied by ZIO. As with callbacks, a Future that continues on other threads needs the agent, or its own context propagation, to keep the context:

```scala mdoc:silent
def runFuture(tracing: Tracing): Task[Int] =
  tracing.span("call-future-api") {
    tracing.scopedEffectFromFuture { implicit ec =>
      scala.concurrent.Future(21 * 2)
    }
  }
```

## Running Everything

Each function takes the `Tracing` service as a parameter, so we can call them from one effect. This compile-only program creates a root span and calls the three functions under it. Replace the layers with your SDK setup:

```scala mdoc:compile-only
import zio.telemetry.opentelemetry.OpenTelemetry

val app: Task[Unit] =
  ZIO
    .serviceWithZIO[Tracing] { tracing =>
      tracing.root("request") {
        runQuery(tracing, "select 1") *> runInstrumentedLibrary(tracing) *> runFuture(tracing)
      }.unit
    }
    .provide(
      OpenTelemetry.noop,
      OpenTelemetry.tracing("com.example.blocking"),
      OpenTelemetry.contextZIO
    )
```

## Going Further

- [Tracing reference](../reference/tracing.md) lists every span method and the scoped variants.
- [Context and Fibers](../concepts/context-and-fibers.md) explains why a fiber and a thread can see different current spans.
- With the Java agent, the thread-local context is the primary storage, so no scoping is needed. See [How to Use the Java Agent](use-the-java-agent.md).
