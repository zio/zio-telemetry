---
id: propagate-context-over-http
title: "How to Propagate Context over HTTP"
sidebar_label: "Propagate Context over HTTP"
description: "Continue one trace across two services by injecting span and baggage data into outgoing headers and extracting them on the receiving side."
keywords:
  - "context propagation"
  - "distributed tracing"
  - "traceparent"
  - "IncomingContextCarrier"
  - "OutgoingContextCarrier"
---

In this guide, we will continue a single trace across a service boundary. The calling service writes the current span and baggage into a set of HTTP headers, and the receiving service reads them back and starts a child span, so a tracing backend shows one trace that spans both services. We keep the HTTP library out of the picture by working with headers as a `Map[String, String]`, which any client or server can produce and consume.

## The Problem

A span lives inside one process. When service A calls service B, B has no way to know which span A was in, so B starts a new, unrelated trace. The backend then shows two disconnected traces and you cannot follow a request across services.

The W3C Trace Context standard solves this with a `traceparent` header that carries the trace and span identifiers, and the W3C Baggage standard carries key-value pairs in a `baggage` header. Writing and parsing those headers by hand is error-prone, and the data has to be put into the OpenTelemetry context in the right place for new spans to pick it up.

## Prerequisites

We use `zio-opentelemetry` and assume a `Tracing` and a `Baggage` service in the environment. The [Context Propagation concept page](../concepts/context-propagation.md) explains the model, and [How to Export Signals with OTLP](export-signals-with-otlp.md) shows how to configure the SDK:

```sbt
// ZIO Telemetry for OpenTelemetry
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

These are the imports for the guide:

```scala mdoc:silent
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable
```

## Writing the Context into Headers

A carrier is the object that holds the propagated data. `OutgoingContextCarrier.default()` creates a carrier backed by a mutable `Map[String, String]`, exposed as its `kernel`. `Tracing#injectSpan` writes the current span into the carrier using the W3C trace context propagator, and `Baggage#inject` writes the current baggage using the W3C baggage propagator.

We put this in a function that returns the headers to attach to an outgoing request:

```scala mdoc:silent
def propagationHeaders(tracing: Tracing, baggage: Baggage): UIO[Map[String, String]] = {
  val carrier = OutgoingContextCarrier.default()

  for {
    _ <- tracing.injectSpan(TraceContextPropagator.default, carrier)
    _ <- baggage.inject(BaggagePropagator.default, carrier)
  } yield carrier.kernel.toMap
}
```

Because both propagators write different header names, sharing one carrier for tracing and baggage is safe. The returned map contains `traceparent`, and `baggage` when baggage was set.

## Sending the Headers

On the calling side, the headers go into the outgoing request with whatever client we use. We call `propagationHeaders` inside a span, so that the span we inject is the one that represents the outgoing call. The `call` parameter stands for the client function that takes the headers and performs the request:

```scala mdoc:silent
def callBackend(tracing: Tracing, baggage: Baggage)(
  call: Map[String, String] => Task[String]
): Task[String] =
  tracing.span("call-backend", SpanKind.CLIENT) {
    for {
      _        <- baggage.set("caller", "proxy")
      headers  <- propagationHeaders(tracing, baggage)
      response <- call(headers)
    } yield response
  }
```

With ZIO HTTP, `call` would build a `Request` and convert the map to `Headers`. The [OpenTelemetry example](../opentelemetry-example.md) does this in `BackendClient`.

## Reading the Context from Headers

On the receiving side, we wrap the request headers in an `IncomingContextCarrier`. The default carrier takes a mutable map, so we copy the headers into one. `Tracing#extractSpan` reads the trace context from the carrier and runs the given effect inside a new child span of the caller's span. We mark the span `SpanKind.SERVER`, which tells the backend that it handles a remote request:

```scala mdoc:silent
def handleRequest[R, E, A](tracing: Tracing, baggage: Baggage, headers: Map[String, String], spanName: String)(
  handler: ZIO[R, E, A]
): ZIO[R, E, A] = {
  val carrier = IncomingContextCarrier.default(mutable.Map.from(headers))

  tracing.extractSpan(TraceContextPropagator.default, carrier, spanName, SpanKind.SERVER)(
    baggage.extract(BaggagePropagator.default, carrier) *> handler
  )
}
```

We extract the baggage inside the span body. `Tracing#extractSpan` builds the child span's context from the incoming headers, so baggage extracted before the span starts would not be visible inside it.

:::tip
If the headers carry no valid `traceparent`, the new span simply has no remote parent and starts a new trace, so the same code serves both calls from instrumented and from plain clients.
:::

## Adapting to Your HTTP Library

The default carriers cover any library whose headers you can turn into a map. To read headers directly from your library's type instead, implement `IncomingContextCarrier[T]` for that type. It needs a `kernel` value, `getAllKeys` and `getByKey`. This sketch is for a plain header list:

```scala mdoc:silent
def listCarrier(initial: List[(String, String)]): IncomingContextCarrier[List[(String, String)]] =
  new IncomingContextCarrier[List[(String, String)]] {
    override val kernel: List[(String, String)] = initial

    override def getAllKeys(carrier: List[(String, String)]): Iterable[String] =
      carrier.map(_._1)

    override def getByKey(carrier: List[(String, String)], key: String): Option[String] =
      carrier.collectFirst { case (name, value) if name.equalsIgnoreCase(key) => value }
  }
```

The example in `opentelemetry-example` does the same for ZIO HTTP's `Headers`. The W3C propagators look up lower-case keys, so make sure `getByKey` compares header names case-insensitively when your library preserves the original case.

## Verifying the Link

We can see how the two sides fit together without a network by passing the outgoing headers straight to the receiving function. This compile-only program plays both services in one process:

```scala mdoc:compile-only
def roundTrip(tracing: Tracing, baggage: Baggage): Task[String] =
  callBackend(tracing, baggage) { headers =>
    handleRequest(tracing, baggage, headers, "/status")(ZIO.succeed("ok"))
  }
```

In a real run, the backend's exported span has the same trace id as the caller's `call-backend` span and names it as its parent.

## Going Further

- The [Baggage reference](../reference/baggage.md) lists all baggage operations.
- [Context Propagation reference](../reference/context-propagation.md) documents the carriers and propagators.
- [The Java agent](use-the-java-agent.md) can propagate context automatically for the HTTP libraries it supports.
