---
id: context-propagation
title: "Context Propagation"
---

Context propagation moves tracing and baggage information across a process boundary: the sender writes it into a message such as HTTP headers, and the receiver reads it back to continue the same trace. The `zio-opentelemetry` module models this with two kinds of values, carriers that hold the transported data and propagators that know how to encode and decode a particular format. The `Tracing` and `Baggage` services combine them in `injectSpan`, `extractSpan`, `Baggage#inject` and `Baggage#extract`.

For the underlying concepts see [Context Propagation](../concepts/context-propagation.md); for a worked HTTP example see [How to Propagate Context over HTTP](../how-to/propagate-context-over-http.md).

## Carriers

A carrier wraps the container that holds the propagated key-value pairs. Both carrier traits extend the Java `TextMapGetter` or `TextMapSetter` and expose the wrapped container as the `kernel` member. Injection and extraction read or write `kernel` using the carrier's own accessors.

The type parameter of a carrier is the type of the container. Because extraction and injection use OpenTelemetry's mutable Java API, injection is not referentially transparent: it mutates the kernel of an `OutgoingContextCarrier`.

### IncomingContextCarrier

```scala
trait IncomingContextCarrier[T] extends ContextCarrier[T] with TextMapGetter[T] {
  val kernel: T
  def getAllKeys(carrier: T): Iterable[String]
  def getByKey(carrier: T, key: String): Option[String]
}
```

An `IncomingContextCarrier` represents data received from an external process. You implement two Scala-friendly methods: `getAllKeys` lists the keys present in the container and `getByKey` returns the value for a key, if any. The Java methods `keys` and `get` are implemented for you on top of them.

The companion provides a ready-made carrier:

```scala
def default(
  initial: mutable.Map[String, String] = mutable.Map.empty
): IncomingContextCarrier[mutable.Map[String, String]]
```

The `default` carrier uses a mutable `Map[String, String]` as its kernel, which starts as the `initial` map you pass.

### OutgoingContextCarrier

```scala
trait OutgoingContextCarrier[T] extends ContextCarrier[T] with TextMapSetter[T] {
  val kernel: T
  def set(carrier: T, key: String, value: String): Unit
}
```

An `OutgoingContextCarrier` represents data to send to an external process. The container must be mutable, because the propagator writes into it through `set`. The companion provides the matching default:

```scala
def default(
  initial: mutable.Map[String, String] = mutable.Map.empty
): OutgoingContextCarrier[mutable.Map[String, String]]
```

Its `set` updates the map. After injection, you read `kernel` and copy the entries into your outgoing message.

### Using the Default Carriers

For simple cases, we create an outgoing carrier, inject into it, and then hand the resulting map to the transport. On the receiving side we wrap the received headers in an incoming carrier:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

val headersToSend: URIO[Tracing, Map[String, String]] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val carrier = OutgoingContextCarrier.default()

    tracing
      .injectSpan(TraceContextPropagator.default, carrier)
      .as(carrier.kernel.toMap)
  }

def handleRequest(receivedHeaders: Map[String, String]): URIO[Tracing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    val carrier = IncomingContextCarrier.default(mutable.Map.from(receivedHeaders))

    tracing.extractSpan(TraceContextPropagator.default, carrier, "handle-request")(ZIO.unit)
  }
```

### Custom Carriers

When the container is not a `mutable.Map`, we implement the trait for its type. The following carriers wrap a hypothetical header collection: the incoming one matches header names case-insensitively, which HTTP requires, and the outgoing one writes into a Java map:

```scala mdoc:compile-only
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}

final case class RequestHeaders(values: Map[String, String])

def incomingFrom(headers: RequestHeaders): IncomingContextCarrier[RequestHeaders] =
  new IncomingContextCarrier[RequestHeaders] {
    override val kernel: RequestHeaders = headers

    override def getAllKeys(carrier: RequestHeaders): Iterable[String] =
      carrier.values.keys

    override def getByKey(carrier: RequestHeaders, key: String): Option[String] =
      carrier.values.collectFirst { case (name, value) if name.equalsIgnoreCase(key) => value }
  }

def outgoingInto(target: java.util.Map[String, String]): OutgoingContextCarrier[java.util.Map[String, String]] =
  new OutgoingContextCarrier[java.util.Map[String, String]] {
    override val kernel: java.util.Map[String, String] = target

    override def set(carrier: java.util.Map[String, String], key: String, value: String): Unit = {
      carrier.put(key, value)
      ()
    }
  }
```

Both carriers are accepted wherever `IncomingContextCarrier[C]` or `OutgoingContextCarrier[C]` is expected, because the propagator is generic in `C`.

## Propagators

A propagator decides which keys are written and how they are encoded. The module defines one trait per signal, each exposing the underlying Java `TextMapPropagator` through its `instance` member, and one `default` value for each.

### TraceContextPropagator

`TraceContextPropagator.default` wraps the W3C Trace Context propagator. It reads and writes the `traceparent` and `tracestate` entries, which is all that is needed to continue a trace in another process. It is the propagator type accepted by `Tracing#injectSpan`, `Tracing#extractSpan` and `Tracing#extractSpanUnsafe`.

### BaggagePropagator

`BaggagePropagator.default` wraps the W3C Baggage propagator. It reads and writes the `baggage` entry. It is the propagator type accepted by `Baggage#inject` and `Baggage#extract`.

Since the two propagators write to different keys, a single carrier and kernel can serve both a trace and baggage at once.

## How the Services Use Them

The four methods that move context across a boundary have these signatures:

```scala
// on Tracing
def injectSpan[C](propagator: TraceContextPropagator, carrier: OutgoingContextCarrier[C]): UIO[Unit]
def extractSpan[C, R, E, E1 <: E, A, A1 <: A](
  propagator: TraceContextPropagator,
  carrier: IncomingContextCarrier[C],
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[E, A] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(zio: => ZIO[R, E1, A1]): ZIO[R, E1, A1]

// on Baggage
def inject[C](propagator: BaggagePropagator, carrier: OutgoingContextCarrier[C]): UIO[Unit]
def extract[C](propagator: BaggagePropagator, carrier: IncomingContextCarrier[C]): UIO[Unit]
```

`Tracing#injectSpan` reads the current context from the `ContextStorage` and passes it to the propagator together with the carrier's `kernel` and the carrier itself as the setter. The effect completes once the entries have been written into the kernel.

`Tracing#extractSpan` runs the propagator against the incoming carrier, starting from an empty root context, and starts a new child span of the extracted remote span. The effect you pass runs inside that child span, and the span ends when the effect finishes. Its name, kind, attributes, status mapper and links work as for `Tracing#span`; see [Tracing](tracing.md). If the carrier holds no valid trace context, the new span has no remote parent. The unsafe variant, `Tracing#extractSpanUnsafe`, returns the span together with an effect that ends it and restores the previous context, which is for callers that manage the span's lifetime themselves.

`Baggage#inject` writes the baggage of the current context into the carrier. When the `Baggage` service was built with `logAnnotated = true`, it first adds the current ZIO log annotations to the baggage. `Baggage#extract` runs the propagator against the incoming carrier and applies the result to the current context, so the received entries become visible to `Baggage#get` and `Baggage#getAll`. Log annotations are merged in first in the same way. See [Baggage](baggage.md) for the service.

`Tracing#extractSpan` does not start from the current context, so baggage that was extracted before the call is not visible inside the new span. The span's context holds only what the trace propagator extracted. Therefore, to restore both the trace and the baggage in a downstream handler, we extract the baggage inside the effect passed to `Tracing#extractSpan`:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

def downstream(received: Map[String, String]): URIO[Tracing & Baggage, Option[String]] =
  for {
    tracing <- ZIO.service[Tracing]
    baggage <- ZIO.service[Baggage]
    carrier  = IncomingContextCarrier.default(mutable.Map.from(received))
    userId  <- tracing.extractSpan(TraceContextPropagator.default, carrier, "downstream")(
                 baggage.extract(BaggagePropagator.default, carrier) *> baggage.get("user-id")
               )
  } yield userId
```
