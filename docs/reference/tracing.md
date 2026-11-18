---
id: tracing
title: "Tracing"
---

`Tracing` is the ZIO service for creating and enriching OpenTelemetry spans. It wraps an OpenTelemetry `Tracer` and a [`ContextStorage`](context-storage.md), so the current span is tracked per fiber and child spans pick up their parent automatically. All methods are defined on the `zio.telemetry.opentelemetry.tracing.Tracing` trait, and every method returns a ZIO effect that you access through the environment with `ZIO.serviceWithZIO[Tracing]`.

This page lists the whole API. For the underlying ideas, see the concept pages on spans, fibers, and context propagation, and for end-to-end walkthroughs see the how-to guides.

## Setting Up the Examples

The examples on this page share one `Tracing` layer. It records finished spans in memory with an `InMemorySpanExporter`, which lets us inspect what the code produced. In an application we would use the layers described in [OpenTelemetry Layers](opentelemetry-layers.md) instead:

```scala mdoc:silent
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

import scala.jdk.CollectionConverters._

val exporter = InMemorySpanExporter.create()

val tracerProvider =
  SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()

val tracingLayer: ULayer[Tracing] =
  (ZLayer.succeed(tracerProvider.get("docs")) ++ ContextStorage.fiberRef) >>> Tracing.live()

def run[E, A](effect: ZIO[Tracing, E, A]): A =
  Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(effect.provideLayer(tracingLayer)).getOrThrowFiberFailure()
  }
```

## Creating the Tracing Service

`Tracing` is built from an OpenTelemetry `Tracer` and a `ContextStorage`. The companion object offers two constructors:

```scala
object Tracing {
  def live(logAnnotated: Boolean = false): URLayer[Tracer & ContextStorage, Tracing]
  def scoped(tracer: Tracer, ctxStorage: ContextStorage, logAnnotated: Boolean = false): URIO[Scope, Tracing]
}
```

`Tracing.live` is the layer version. `Tracing.scoped` returns the service in a `Scope`, which is handy when you build the service by hand. In both cases the `logAnnotated` flag enables [log annotation propagation](#log-annotations-as-attributes). When the scope closes, the service ends the span that is current in the context storage at that moment, which is normally none. Most applications do not call these directly and use `OpenTelemetry.tracing` instead, which also builds the `Tracer` from an `OpenTelemetry` instance.

## Common Parameters

The span-creating methods share a set of parameters. The table below describes them once, and the method sections only mention what differs:

| Parameter      | Type                      | Default                | Meaning                                                           |
|----------------|---------------------------|------------------------|-------------------------------------------------------------------|
| `spanName`     | `String`                  | none                   | Name of the span                                                  |
| `spanKind`     | `SpanKind`                | `SpanKind.INTERNAL`    | Role of the span: `INTERNAL`, `SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER` |
| `attributes`   | `Attributes`              | `Attributes.empty()`   | Attributes set when the span starts, see [Attributes](attributes.md) |
| `statusMapper` | `StatusMapper[E, A]`      | `StatusMapper.default` | Maps the result to the span status, see [StatusMapper](status-mapper.md) |
| `links`        | `Seq[SpanContext]`        | `Seq.empty`            | Spans causally related to this one without being its parent       |

Every span records its start and end time using the ZIO `Clock`, so tests that use `TestClock` control span timestamps. The `spanName`, `spanKind`, `attributes`, and `links` are fixed at span creation, while `statusMapper` is evaluated when the body finishes.

Links connect a span to other spans, for example the many messages a batch consumer processes. We create a link by passing the `SpanContext` of the other span. The `getCurrentSpanContextUnsafe` method described later gives the context of the current span:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    for {
      linked <- tracing.span("producer")(tracing.getCurrentSpanContextUnsafe)
      _      <- tracing.span("consumer", spanKind = SpanKind.CONSUMER, links = List(linked))(ZIO.unit)
    } yield ()
  }
```

## Creating Spans

### span

`Tracing#span` runs an effect inside a new span that is a child of the current span. The span starts before the effect runs and ends when the effect finishes, whether it succeeds, fails, or is interrupted:

```scala
def span[R, E, E1 <: E, A, A1 <: A](
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[E, A] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]
```

While the body runs, the new span is the current span, so nested calls become its children. If there is no current span, the new span is a root of a new trace. We nest two spans and look at what the in-memory exporter collected:

```scala mdoc
val nested = ZIO.serviceWithZIO[Tracing] { tracing =>
  tracing.span("parent", attributes = Attributes(Attribute.string("user.id", "u-42"))) {
    tracing.span("child")(ZIO.unit)
  }
}

run(nested)

exporter.getFinishedSpanItems.asScala.toList.map(s => (s.getName, s.getParentSpanContext.isValid))
```

The child ends first and reports a valid parent, while the parent has none.

### root

`Tracing#root` is like `span`, but it ignores the current span and starts a new trace. It takes the same parameters as `span`:

```scala
def root[R, E, E1 <: E, A, A1 <: A](
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[E, A] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]
```

We use it for work that should not belong to the caller's trace, such as a background job started from a request. Combining it with `links` keeps the relationship visible:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    for {
      origin <- tracing.getCurrentSpanContextUnsafe
      _      <- tracing.root("background-job", links = List(origin))(ZIO.unit)
    } yield ()
  }
```

### spanScoped

`Tracing#spanScoped` ties the span lifetime to a `Scope` instead of wrapping an effect. The span starts when the effect runs, becomes current for the rest of the scope, and ends when the scope closes:

```scala
def spanScoped(
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(implicit trace: Trace): ZIO[Scope, Nothing, Unit]
```

It is useful in `for` comprehensions and layers, where wrapping everything in a function call would be awkward. Several `spanScoped` calls in one scope nest in order, so the second span is a child of the first. The `statusMapper` is evaluated only if the scope closes with a failure, and only its failure side matters, see [StatusMapper](status-mapper.md#scoped-spans). A scope that closes successfully never sets a status.

The following program opens two spans in a single scope and then runs work under both of them:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    ZIO.scoped {
      for {
        _ <- tracing.spanScoped("request")
        _ <- tracing.spanScoped("handler", spanKind = SpanKind.SERVER)
        _ <- tracing.setAttribute("route", "/orders")
      } yield ()
    }
  }
```

### inSpan

`Tracing#inSpan` creates a child span of an externally provided `Span`, which does not have to be the current span. This is intended for interoperation with code that already manages spans, for example Future-based libraries:

```scala
def inSpan[R, E, E1 <: E, A, A1 <: A](
  span: Span,
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[E, A] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]
```

`inSpan` ends only the child span it creates. The caller remains responsible for ending the external span. The parent is exactly the `span` argument, so the current span is not involved.

We parent a new span to a span obtained from elsewhere. Here an invalid placeholder stands in for the external span:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.Span

def external: Span = Span.getInvalid

val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.inSpan(external, "child-of-external")(ZIO.unit)
  }
```

### extractSpan

`Tracing#extractSpan` continues a trace that started in another process. It reads the trace context from an `IncomingContextCarrier` using a `TraceContextPropagator`, and creates a span whose parent is the extracted remote span:

```scala
def extractSpan[C, R, E, E1 <: E, A, A1 <: A](
  propagator: TraceContextPropagator,
  carrier: IncomingContextCarrier[C],
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  statusMapper: StatusMapper[E, A] = StatusMapper.default,
  links: Seq[SpanContext] = Seq.empty
)(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]
```

The parent comes only from the carrier, not from the current span. If the carrier holds no trace context, the new span has no parent. `TraceContextPropagator.default` implements W3C Trace Context, and `IncomingContextCarrier.default` wraps a mutable `Map[String, String]`, for example the headers of a request. Carriers and propagators are described in [Context Propagation](context-propagation.md).

The next example puts a W3C `traceparent` header into a map and continues the trace from it:

```scala mdoc:compile-only
import scala.collection.mutable

val headers = mutable.Map("traceparent" -> "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.extractSpan(
      TraceContextPropagator.default,
      IncomingContextCarrier.default(headers),
      "handle-request",
      spanKind = SpanKind.SERVER
    )(ZIO.unit)
  }
```

### injectSpan

`Tracing#injectSpan` writes the current span context into an `OutgoingContextCarrier`, so the data can travel with an outgoing request or message:

```scala
def injectSpan[C](
  propagator: TraceContextPropagator,
  carrier: OutgoingContextCarrier[C]
)(implicit trace: Trace): UIO[Unit]
```

`OutgoingContextCarrier.default` accepts a mutable map and fills it. The example injects the context of a span and then reads the resulting header:

```scala mdoc
import scala.collection.mutable

val outgoingHeaders = mutable.Map.empty[String, String]

run(
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("client-call", spanKind = SpanKind.CLIENT) {
      tracing.injectSpan(TraceContextPropagator.default, OutgoingContextCarrier.default(outgoingHeaders))
    }
  }
)

outgoingHeaders.keys.toList
```

## Unsafe Span Management

The "unsafe" methods leave the span lifecycle to the caller. They are meant for interoperating with code that cannot be expressed as a single wrapped effect, and they are easy to misuse, so prefer `span`, `spanScoped`, and `extractSpan` where possible.

### spanUnsafe

`Tracing#spanUnsafe` starts a child of the current span and makes it the current span. It returns the `Span` together with a finalizer effect:

```scala
def spanUnsafe(
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  links: Seq[SpanContext] = Seq.empty
)(implicit trace: Trace): UIO[(Span, UIO[Any])]
```

There is no `statusMapper` parameter. The finalizer ends the current span and restores the previous context, and you must run it, otherwise the span never ends and the context is not restored. It does not run automatically on failure, so use it inside `ZIO.acquireRelease` or an `ensuring`:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    ZIO.acquireReleaseWith(tracing.spanUnsafe("manual")) { case (_, finalize) => finalize } { case (span, _) =>
      ZIO.succeed(span.setAttribute("custom", "value")).unit
    }
  }
```

### extractSpanUnsafe

`Tracing#extractSpanUnsafe` combines `extractSpan` with the unsafe lifecycle. It takes the same propagator, carrier, name, kind, attributes, and links but no status mapper, and returns the `Span` with its finalizer:

```scala
def extractSpanUnsafe[C](
  propagator: TraceContextPropagator,
  carrier: IncomingContextCarrier[C],
  spanName: String,
  spanKind: SpanKind = SpanKind.INTERNAL,
  attributes: Attributes = Attributes.empty(),
  links: Seq[SpanContext] = Seq.empty
)(implicit trace: Trace): UIO[(Span, UIO[Any])]
```

The finalizer ends the span and restores the context that was current before the call.

### Reading the Current Context

Three methods expose what the context storage currently holds. They return the raw OpenTelemetry objects, which is the reason for the `Unsafe` suffix: you can mutate or end the span they return.

| Method                                     | Returns                       |
|--------------------------------------------|-------------------------------|
| `getCurrentContextUnsafe`                  | `UIO[Context]`                |
| `getCurrentSpanUnsafe`                     | `UIO[Span]`                   |
| `getCurrentSpanContextUnsafe`              | `UIO[SpanContext]`            |

If no span is active, `getCurrentSpanUnsafe` returns the invalid no-op span that `Span.fromContext` yields for a context without a span.

## Events and Attributes

### addEvent

`Tracing#addEvent` adds a named, timestamped event to the current span. `Tracing#addEventWithAttributes` adds an event with an `Attributes` value:

```scala
def addEvent(name: String)(implicit trace: Trace): UIO[Unit]
def addEventWithAttributes(name: String, attributes: Attributes)(implicit trace: Trace): UIO[Unit]
```

The event timestamp comes from the ZIO `Clock`:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("checkout") {
      tracing.addEvent("cart-validated") *>
        tracing.addEventWithAttributes("payment-authorized", Attributes(Attribute.string("provider", "acme")))
    }
  }
```

### setAttribute

`Tracing#setAttribute` sets an attribute on the current span. It is overloaded for the attribute types OpenTelemetry supports. The `Attribute` and `AttributeKey` overloads are generic, and the others take a name:

| Overload                                              | Attribute type    |
|-------------------------------------------------------|-------------------|
| `setAttribute(name: String, value: Boolean)`          | boolean           |
| `setAttribute(name: String, value: Double)`           | double            |
| `setAttribute(name: String, value: Long)`             | long              |
| `setAttribute(name: String, value: String)`           | string            |
| `setAttribute(name: String, values: Seq[String])`     | string array      |
| `setAttribute(name: String, values: Seq[Boolean])`    | boolean array     |
| `setAttribute(name: String, values: Seq[Long])`       | long array        |
| `setAttribute(name: String, values: Seq[Double])`     | double array      |
| `setAttribute[T](key: AttributeKey[T], value: T)`     | the key's type    |
| `setAttribute[T](attribute: Attribute[T])`            | the attribute's type |

All overloads return `UIO[Unit]`. The `Seq[Boolean]`, `Seq[Long]`, and `Seq[Double]` overloads take extra implicit `DummyImplicit` parameters so that they do not clash with `Seq[String]` after type erasure. You never pass these yourself. Scala callers only notice them when passing the method as a function value.

We set attributes using the different forms:

```scala mdoc:compile-only
val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("work") {
      tracing.setAttribute("retries", 3L) *>
        tracing.setAttribute("tags", Seq("a", "b")) *>
        tracing.setAttribute(Attribute.boolean("cache.hit", true)) *>
        tracing.setAttribute(AttributeKey.stringKey("user.id"), "u-42")
    }
  }
```

The `Attribute` and `Attributes` helpers are documented in [Attributes](attributes.md).

### Log Annotations as Attributes

When the service is created with `logAnnotated = true`, every span created afterwards starts with the current ZIO log annotations (`ZIO.logAnnotate`) as string attributes. Attributes passed through the `attributes` parameter are added on top, so on a key collision the explicit attribute wins. The flag defaults to `false`:

```scala mdoc:compile-only
val annotatedLayer: ULayer[Tracing] =
  (ZLayer.succeed(tracerProvider.get("docs")) ++ ContextStorage.fiberRef) >>> Tracing.live(logAnnotated = true)

val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    ZIO.logAnnotate("request.id", "r-1") {
      tracing.span("annotated")(ZIO.unit)
    }
  }
```

The `annotated` span carries `request.id` as an attribute. The annotations are read when the span starts, so later changes do not update it.

## Non-ZIO Context Propagation

Instrumented Java or Scala libraries find the current span through the thread-local OpenTelemetry `Context`. ZIO fibers do not set it on their own, so the following methods run foreign code with the current ZIO context made current on the thread, and close the scope right after:

```scala
def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A]
def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A]
def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]
```

`scopedEffect` captures exceptions thrown by the code in the failure channel, while `scopedEffectTotal` assumes the code does not throw. `scopedEffectFromFuture` makes the context current only while `make` creates the `Future`, so it is the caller's responsibility to carry the context into the Future's callbacks. The Java agent provides such a mechanism, see [How to Use the Java Agent](../how-to/use-the-java-agent.md).

We call a library from inside a span so that it sees the right parent:

```scala mdoc:compile-only
import scala.concurrent.Future

def legacyCall(): String = "ok"

val program: ZIO[Tracing, Throwable, String] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("legacy") {
      tracing.scopedEffect(legacyCall()) *>
        tracing.scopedEffectFromFuture(implicit ec => Future(legacyCall()))
    }
  }
```

## Aspects

Every span-creating method has a `ZIOAspect` version in `Tracing#aspects`, for use with the `@@` operator. The aspects are `span`, `root`, `inSpan`, and `extractSpan`, and they take the same parameters as the methods, minus the wrapped effect. Aspects read well when the instrumentation should sit beside the code instead of around it:

```scala
object aspects {
  def span[E1, A1](spanName: String, spanKind: SpanKind = ..., attributes: Attributes = ..., statusMapper: StatusMapper[E1, A1] = ..., links: Seq[SpanContext] = ...): ZIOAspect[...]
  def root[E1, A1](...): ZIOAspect[...]
  def inSpan[E1, A1](span: Span, spanName: String, ...): ZIOAspect[...]
  def extractSpan[C, E1, A1](propagator: TraceContextPropagator, carrier: IncomingContextCarrier[C], spanName: String, ...): ZIOAspect[...]
}
```

The `aspects` object belongs to a `Tracing` instance, so we import its members from the value:

```scala mdoc
val aspectProgram = ZIO.serviceWithZIO[Tracing] { tracing =>
  import tracing.aspects._

  ZIO.unit @@ span("inner") @@ span("outer")
}

run(aspectProgram)

exporter.getFinishedSpanItems.asScala.toList.map(_.getName).takeRight(2)
```

Aspects apply from left to right, so the aspect written last wraps all the others and becomes the outermost span. The `inner` span ends first.

## Using Status Mappers

Every span method accepts a `StatusMapper`. The next example marks the span as `OK` on success and records the exception on failure. The full semantics are in [StatusMapper](status-mapper.md):

```scala mdoc:compile-only
val mapper =
  StatusMapper.both(
    StatusMapper.failureThrowable(_ => StatusCode.ERROR),
    StatusMapper.successNoDescription[Unit](_ => StatusCode.OK)
  )

val program: ZIO[Tracing, Throwable, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("work", statusMapper = mapper)(ZIO.unit)
  }
```
