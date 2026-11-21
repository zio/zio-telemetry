---
id: migrate-from-opentracing-or-opencensus
title: "How to Migrate from OpenTracing or OpenCensus"
description: "Move code that uses the zio-opentracing or zio-opencensus modules to the zio-opentelemetry Tracing service, with a side-by-side API mapping."
keywords:
  - "OpenTracing"
  - "OpenCensus"
  - "OpenTelemetry"
  - "migration"
---

In this guide, we will move code from the `zio-opentracing` or `zio-opencensus` modules to `zio-opentelemetry`. By the end, you have replaced the old tracing service with `Tracing`, translated spans, tags, errors and context propagation, and removed the legacy dependency.

OpenTracing and OpenCensus were merged into OpenTelemetry and are no longer developed upstream, so the migration is also the way to keep receiving fixes and new features. The old modules are described in [OpenTracing](../opentracing.md) and [OpenCensus](../opencensus.md).

We first change the dependency and the layers, then translate the API call by call, and finally deal with the differences in error handling and propagation.

## The Problem

Both legacy modules wrap a Java library that is no longer maintained. Staying on them means that new tracing backends, semantic conventions and instrumentation libraries are not available to you, and the two APIs differ from each other, so services written against different modules do not share idioms.

The two old services look like this. This is the old code, `zio-opentracing` (not compiled here):

```scala
def charge(orderId: String): ZIO[OpenTracing, Throwable, Unit] =
  ZIO.serviceWithZIO[OpenTracing] { tracing =>
    tracing.span("charge-card") {
      tracing.tag("order.id", orderId) *> tracing.log("charging")
    }
  }
```

This is the old code, `zio-opencensus` (not compiled here):

```scala
def charge(orderId: String): ZIO[Tracing, Throwable, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("charge-card", attributes = Map("order.id" -> orderId))(ZIO.unit)
  }
```

Note that both modules name their service differently, `OpenTracing` and a `Tracing` from `zio.telemetry.opencensus`. In the new code, the service is `zio.telemetry.opentelemetry.tracing.Tracing`.

## Prerequisites

We replace the old module with `zio-opentelemetry` and add the OpenTelemetry SDK for the exporter you use. Remove `zio-opentracing` or `zio-opencensus` once the last usage is gone. They can live side by side during a gradual migration:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry OpenTelemetry module
  "dev.zio"         %% "zio-opentelemetry" % "@VERSION@",
  // OpenTelemetry SDK that creates and exports the spans
  "io.opentelemetry" % "opentelemetry-sdk" % "1.68.0"
)
```

Configuring the SDK and an exporter is covered in [How to Export Signals with OTLP](export-signals-with-otlp.md). Here we assume an `OpenTelemetry` layer exists. The examples use these imports:

```scala mdoc:silent
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
```

## Replacing the Layers

The old modules need a `Tracer` from the respective Java library and turn it into the service with `OpenTracing.live` or `zio.telemetry.opencensus.Tracing.live`. The new module needs an `OpenTelemetry` instance, a `ContextStorage`, and an instrumentation scope name, which identifies your library or service in the exported data:

```scala mdoc:silent
def tracingLayer: URLayer[io.opentelemetry.api.OpenTelemetry, Tracing] =
  ZLayer.makeSome[io.opentelemetry.api.OpenTelemetry, Tracing](
    OpenTelemetry.contextZIO,
    OpenTelemetry.tracing("com.example.orders")
  )
```

`OpenTracing.noop` corresponds to `OpenTelemetry.noop`, which provides an `OpenTelemetry` instance that records nothing. See [OpenTelemetry Layers](../reference/opentelemetry-layers.md) for the other layers.

## Translating Spans

Span creation keeps the same shape: a method or an aspect wraps an effect, makes the span current while the effect runs, and ends it afterwards. The following table lists the span methods side by side:

| OpenTracing                              | OpenCensus                                    | OpenTelemetry                                   |
|------------------------------------------|-----------------------------------------------|-------------------------------------------------|
| `span(operation)(zio)`                   | `span(name, kind, toErrorStatus, attributes)` | `Tracing#span(spanName, spanKind, attributes, statusMapper, links)` |
| `root(operation)(zio)`                   | `root(name, kind, toErrorStatus, attributes)` | `Tracing#root(spanName, spanKind, attributes, statusMapper, links)` |
| `spanFrom(format, carrier, operation)`   | `fromRootSpan(format, carrier, getter, name)` | `Tracing#extractSpan(propagator, carrier, spanName)` |
| `inject(format, carrier)`                | `inject(format, carrier, setter)`             | `Tracing#injectSpan(propagator, carrier)`       |
| `getCurrentSpanUnsafe`                   | `getCurrentSpan`                              | `Tracing#getCurrentSpanUnsafe`                  |
| `aspects.span`, `aspects.root`, `aspects.spanFrom` | `aspects.span`, `aspects.root`, `aspects.fromRootSpan` | `Tracing#aspects.span`, `aspects.root`, `aspects.extractSpan` |

Translating the examples from the beginning of the guide gives the following code. The span name moves into `spanName`, and the span body stays as is:

```scala mdoc:compile-only
def charge(orderId: String): ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("charge-card") {
      tracing.setAttribute("order.id", orderId) *> tracing.addEvent("charging")
    }
  }
```

The aspect form from `Tracing#aspects` is equivalent and often reads better when the span wraps an existing effect:

```scala mdoc:compile-only
def chargeWithAspect(orderId: String): ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    import tracing.aspects._

    tracing.setAttribute("order.id", orderId) @@ span("charge-card")
  }
```

:::warning
`zio-opencensus` creates spans of kind `SERVER` unless told otherwise, while `Tracing#span` defaults to `SpanKind.INTERNAL`. When you migrate a handler for incoming requests, pass `spanKind = SpanKind.SERVER` explicitly, otherwise the backend shows the span as an internal operation. `Span.Kind.CLIENT` becomes `SpanKind.CLIENT`.
:::

`zio-opentracing` also creates an initial root span, named `ROOT` by default, when the layer is built, and every span is a child of it unless started with `OpenTracing#root`. `Tracing` has no such initial span. A span created while no other span is current is the root of a new trace, so you can drop `rootOperation` from the layer configuration.

## Translating Tags, Attributes and Logs

OpenTracing tags and OpenCensus attributes become OpenTelemetry attributes. OpenTracing logs become span events. The following table maps them:

| OpenTracing                    | OpenCensus                             | OpenTelemetry                                           |
|--------------------------------|----------------------------------------|---------------------------------------------------------|
| `tag(key, value)`              | `putAttributes(Map(key -> value))`     | `Tracing#setAttribute(name, value)`                     |
| `log(msg)`                     | none                                   | `Tracing#addEvent(name)`                                |
| `log(fields)`                  | none                                   | `Tracing#addEventWithAttributes(name, attributes)`      |
| none                           | `attributes` parameter of `span`       | `attributes` parameter of `Tracing#span`                |
| `setBaggageItem`, `getBaggageItem` | none                               | `Baggage#set`, `Baggage#get`                            |

`Tracing#setAttribute` has overloads for `String`, `Boolean`, `Long`, `Double`, their sequences, and for typed `Attribute` values. OpenCensus needs its implicit conversions to build the `AttributeValue` map, which is not needed any more.

Attributes that are known when the span starts go into the `attributes` parameter. We build them with `Attributes`, and each element is an `Attribute` created from one of its typed factories:

```scala mdoc:compile-only
def chargeWithAttributes(orderId: String, amountCents: Long): ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span(
      "charge-card",
      attributes = Attributes(
        Attribute.string("order.id", orderId),
        Attribute.long("order.amount.cents", amountCents)
      )
    )(ZIO.unit)
  }
```

OpenTracing log fields are an arbitrary `Map[String, ?]`. The nearest equivalent is an event with typed attributes:

```scala mdoc:compile-only
def logStateChange(from: String, to: String): ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.addEventWithAttributes(
      "state-change",
      Attributes(Attribute.string("from", from), Attribute.string("to", to))
    )
  }
```

Baggage lives in its own service, `Baggage`, which has a separate layer. See [Baggage](../reference/baggage.md).

## Translating Error Handling

The legacy modules differ in how they report a failed span. `zio-opentracing` tags the span with `error=true` and logs the cause, controlled by the `tagError` and `logError` parameters. `zio-opencensus` sets a `Status`, `UNKNOWN` unless an `ErrorMapper` maps the failure. In both modules a successful span is left alone.

OpenTelemetry uses the span status, and the status is controlled by a `StatusMapper`:

| Legacy setting                          | OpenTelemetry                                                    |
|-----------------------------------------|------------------------------------------------------------------|
| `tagError = true` (default)             | Default behavior: failed spans get status `ERROR`.               |
| `logError = true` (default)             | The status description holds the pretty-printed `Cause`.         |
| `ErrorMapper(pf)` returning a `Status`  | `StatusMapper.failureNoException`, `StatusMapper.failure`, or `StatusMapper.failureThrowable` |
| `tagError = false` or `logError = false` | `StatusMapper.failureNoException(_ => StatusCode.UNSET)` leaves the span unset and records nothing |

An `ErrorMapper` that decides the status per error type translates into a failure mapper. The OpenCensus line is the old code (not compiled here):

```scala
ErrorMapper[AppError] { case NotFound => Status.NOT_FOUND }
```

In the new API, the equivalent is:

```scala mdoc:silent
sealed trait AppError
case object NotFound extends AppError
case object Broken   extends AppError

def statusMapper: StatusMapper.Failure[AppError] =
  StatusMapper.failureNoException[AppError] {
    case NotFound => StatusCode.UNSET
    case Broken   => StatusCode.ERROR
  }
```

OpenTelemetry has only the three status codes `UNSET`, `OK` and `ERROR`, so the finer OpenCensus statuses such as `NOT_FOUND` or `UNAVAILABLE` have no direct equivalent. Put that detail into an attribute or into the status description of an `ERROR` status instead. [How to Customize Span Status](customize-span-status.md) covers the details, including the success mappers that have no legacy counterpart.

## Translating Context Propagation

Propagation changes the most, because OpenTracing and OpenCensus pass a library-specific `Format` or `TextFormat` together with a carrier, and OpenTelemetry passes a propagator together with a carrier wrapper.

| Legacy                                   | OpenTelemetry                                      |
|------------------------------------------|----------------------------------------------------|
| OpenTracing `Format[C]`, OpenCensus `TextFormat` | `TraceContextPropagator.default`, the W3C Trace Context propagator |
| Carrier `C` with a `TextMap` or `Getter` | `IncomingContextCarrier.default(initial)`           |
| Carrier `C` with a `TextMap` or `Setter` | `OutgoingContextCarrier.default(initial)`           |

Both default carriers wrap a mutable `Map[String, String]`. For an outgoing call, we create the carrier, ask `Tracing#injectSpan` to fill it, and send its contents as headers:

```scala mdoc:compile-only
def outgoingHeaders: URIO[Tracing, Map[String, String]] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val carrier = OutgoingContextCarrier.default()

    tracing.injectSpan(TraceContextPropagator.default, carrier).as(carrier.kernel.toMap)
  }
```

On the receiving side, we wrap the incoming headers in an `IncomingContextCarrier` and start the span from it:

```scala mdoc:compile-only
def handle(headers: Map[String, String]): ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val carrier = IncomingContextCarrier.default(scala.collection.mutable.Map.from(headers))

    tracing.extractSpan(
      TraceContextPropagator.default,
      carrier,
      "handle-request",
      SpanKind.SERVER
    )(ZIO.unit)
  }
```

:::warning
The old modules usually propagate with their own wire formats, such as Zipkin B3 or the Uber trace id. Services in a mixed environment must agree on the format, and the default propagator uses the W3C `traceparent` header. While some services are still on the legacy modules, make sure that both sides understand the same headers. [How to Propagate Context over HTTP](propagate-context-over-http.md) shows the complete flow.
:::

## Migrating Gradually

The old and new modules do not share context, so a trace is broken at every place where an old-style span calls into new-style code in the same process. Migrate in this order to keep traces whole:

1. Migrate one service at a time, starting with the services that have no downstream callers.
2. Within a service, switch every span creation and attribute call to `Tracing` in one change, and replace the layer last.
3. Keep the wire format compatible between migrated and not yet migrated services until all services are done, then remove the legacy dependency.

## Going Further

The [OpenTelemetry module](../opentelemetry.md) page lists the reference pages for everything used above, and [Tracing](../reference/tracing.md) documents the complete span API. To see the outcome of your migration in tests, follow [How to Test Traced Code](test-traced-code.md).
