---
id: baggage
title: "Baggage"
---

Baggage is a set of string key/value entries that travels with the OpenTelemetry context. Entries set in one service can be read in downstream services after propagation, and they are independent of spans. The `Baggage` service reads and modifies the baggage of the current fiber's context, and injects it into or extracts it from a carrier.

## Creating the Baggage Layer

`OpenTelemetry.baggage` builds the service from a `ContextStorage`:

```scala
object OpenTelemetry {
  def baggage(logAnnotated: Boolean = false): URLayer[ContextStorage, Baggage]
}
```

`Baggage.live(logAnnotated)` in `zio.telemetry.opentelemetry.baggage` is the same layer. To create it with the fiber-local storage:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.baggage.Baggage

def baggageLayer: ULayer[Baggage] =
  OpenTelemetry.contextZIO >>> OpenTelemetry.baggage()
```

## Reading and Writing Entries

The `Baggage` trait exposes these methods. Writes update the context held by the context storage, so they are visible to later operations of the same fiber, including span creation:

```scala
trait Baggage {
  def set(name: String, value: String): UIO[Unit]
  def setWithMetadata(name: String, value: String, metadata: String): UIO[Unit]
  def get(name: String): UIO[Option[String]]
  def getAll: UIO[Map[String, String]]
  def getAllWithMetadata: UIO[Map[String, (String, String)]]
  def remove(name: String): UIO[Unit]
  def inject[C](propagator: BaggagePropagator, carrier: OutgoingContextCarrier[C]): UIO[Unit]
  def extract[C](propagator: BaggagePropagator, carrier: IncomingContextCarrier[C]): UIO[Unit]
  def getCurrentBaggageUnsafe: UIO[io.opentelemetry.api.baggage.Baggage]
}
```

`Baggage#set` adds or replaces an entry, and `Baggage#setWithMetadata` also stores an opaque metadata string with it. `Baggage#get` returns the value of an entry if present. `Baggage#getAll` returns all entries as a map of name to value, and `Baggage#getAllWithMetadata` returns a map of name to a pair of value and metadata. `Baggage#remove` deletes an entry. To set, read and remove entries:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage

val program: URIO[Baggage, Unit] =
  for {
    baggage <- ZIO.service[Baggage]
    _       <- baggage.set("user.id", "u-1")
    _       <- baggage.setWithMetadata("tenant", "acme", "internal")
    user    <- baggage.get("user.id")
    all     <- baggage.getAllWithMetadata
    _       <- baggage.remove("user.id")
    _       <- ZIO.logInfo(s"$user $all")
  } yield ()
```

`Baggage#getCurrentBaggageUnsafe` returns the underlying Java `io.opentelemetry.api.baggage.Baggage` of the current context. It is an escape hatch for code that needs the Java API.

## Propagating Baggage

`Baggage#inject` writes the current baggage into an `OutgoingContextCarrier` with a `BaggagePropagator`, and `Baggage#extract` reads baggage from an `IncomingContextCarrier` into the current context. `BaggagePropagator.default` implements the W3C Baggage format, which uses the `baggage` header. `OutgoingContextCarrier.default` and `IncomingContextCarrier.default` wrap a mutable `Map[String, String]` that is exposed as the `kernel`. To pass baggage from one side to the other through a map:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}

import scala.collection.mutable

val program: URIO[Baggage, Option[String]] =
  for {
    baggage  <- ZIO.service[Baggage]
    outgoing  = OutgoingContextCarrier.default()
    _        <- baggage.set("user.id", "u-1")
    _        <- baggage.inject(BaggagePropagator.default, outgoing)
    incoming  = IncomingContextCarrier.default(mutable.Map.empty ++ outgoing.kernel)
    _        <- baggage.extract(BaggagePropagator.default, incoming)
    value    <- baggage.get("user.id")
  } yield value
```

For HTTP carriers, see [How to Propagate Context over HTTP](../how-to/propagate-context-over-http.md) and [Context Propagation](context-propagation.md).

## Log Annotations as Baggage

With `logAnnotated = true`, the service copies the ZIO log annotations of the calling fiber into the baggage before every operation. The copied entries carry the metadata `zio log annotation`. If a name exists in both the baggage and the annotations, the baggage entry wins, so `Baggage#set` overrides an annotated value. `Baggage#remove` cannot remove a name that comes from a log annotation, because the annotation is copied again on the next call. To see an annotation through the baggage:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.baggage.Baggage

val program: UIO[Option[String]] =
  ZIO
    .serviceWithZIO[Baggage] { baggage =>
      ZIO.logAnnotate("request.id", "r-42") {
        baggage.get("request.id")
      }
    }
    .provide(OpenTelemetry.contextZIO >>> OpenTelemetry.baggage(logAnnotated = true))
```

## Related Pages

Baggage is stored in the same context as the active span, see [Context Storage](context-storage.md) and [Context and Fibers](../concepts/context-and-fibers.md).
