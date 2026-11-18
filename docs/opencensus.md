---
id: opencensus
title: "OpenCensus"
---

:::warning
The OpenCensus project is deprecated upstream and has been merged into OpenTelemetry. The `zio-opencensus` module exists for applications that already use it. For new code, use the [OpenTelemetry module](opentelemetry.md), and see [How to Migrate from OpenTracing or OpenCensus](how-to/migrate-from-opentracing-or-opencensus.md) to move an existing application.
:::

The `zio-opencensus` module wraps the tracing part of [OpenCensus](https://opencensus.io/) in a ZIO service named `Tracing`. Each effect that runs inside a span records its timing, attributes, and error status through the OpenCensus `Tracer` that you provide. This page lists every public member of the module. The module covers tracing only; it has no metrics or logging support.

## Installation

Add the module to your `build.sbt`. It depends on `opencensus-api` and `opencensus-impl` and brings both transitively:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenCensus (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opencensus" % "@VERSION@"
)
```

The examples on this page share the following imports. The OpenCensus `Tracing` object is renamed so that it does not clash with the ZIO Telemetry `Tracing` service:

```scala mdoc:silent
import io.opencensus.trace.{AttributeValue, Span, SpanContext, Status, Tracer, Tracing => OcTracing}
import zio._
import zio.telemetry.opencensus.{ErrorMapper, Tracing}
```

## Creating the Service

The `Tracing` trait is the service. It is created by one of the constructors in the `Tracing` companion object, each of which needs an OpenCensus `Tracer`.

### Tracing.live

`Tracing.live` builds the service as a layer from a `Tracer` in the environment. It is a `val`, so it takes no parentheses:

```scala
val live: URLayer[Tracer, Tracing]
```

The initial current span is a blank span that records nothing, so no span exists until you start one. The following example builds the layer from the default tracer of the OpenCensus runtime:

```scala mdoc:silent
val tracerLayer: ULayer[Tracer] =
  ZLayer.succeed(OcTracing.getTracer)

val tracingLayer: ULayer[Tracing] =
  tracerLayer >>> Tracing.live
```

### Tracing.scoped

`Tracing.scoped` builds the service as a scoped effect from an explicit tracer:

```scala
def scoped(tracer: Tracer): URIO[Scope, Tracing]
```

It is what `Tracing.live` uses internally. Use it when you already hold a `Tracer` value, for example in a test:

```scala mdoc:silent
val scopedTracing: URIO[Scope, Tracing] =
  Tracing.scoped(OcTracing.getTracer)
```

## Spans

The methods in this section start spans. They share the following parameters:

- `name` is the span name.
- `kind` is the OpenCensus `Span.Kind`, either `SERVER` or `CLIENT`; it defaults to `SERVER`.
- `toErrorStatus` is an [ErrorMapper](#error-mapping) that decides the span status when the effect fails; it defaults to `ErrorMapper.default`.
- `attributes` is a `Map[String, AttributeValue]` that is added to the span before the effect runs; it defaults to empty.

The span ends, and the previous current span is restored, whether the effect succeeds, fails, or is interrupted. The failure itself is not handled; it is passed on unchanged.

The examples below share the following helper, which makes the service available through an environment parameter:

```scala mdoc:silent
def withTracing[R, E, A](f: Tracing => ZIO[R, E, A]): ZIO[R, E, A] =
  ZIO.serviceWithZIO[Tracing](f).provideSomeLayer[R](tracingLayer)
```

### Tracing#root

`Tracing#root` starts a new span that has no parent and makes it the current span for the duration of `zio`:

```scala
def root[R, E, E1 <: E, A](
  name: String,
  kind: Span.Kind = Span.Kind.SERVER,
  toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
  attributes: Map[String, AttributeValue] = Map.empty
)(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]
```

The following example wraps a request handler in a root span:

```scala mdoc:compile-only
val handler: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("handle-request")(ZIO.unit)
  }
```

### Tracing#span

`Tracing#span` starts a child of the current span and makes it the current span for the duration of `zio`. If no span is current, the child is a root span:

```scala
def span[R, E, E1 <: E, A](
  name: String,
  kind: Span.Kind = Span.Kind.SERVER,
  toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
  attributes: Map[String, AttributeValue] = Map.empty
)(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]
```

The following example starts a client span inside a root span:

```scala mdoc:compile-only
val nested: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("handle-request") {
      tracing.span("call-backend", kind = Span.Kind.CLIENT)(ZIO.unit)
    }
  }
```

### Tracing#fromRemoteSpan

`Tracing#fromRemoteSpan` starts a span whose parent is the given `SpanContext`, which you obtained from another process:

```scala
def fromRemoteSpan[R, E, E1 <: E, A](
  remote: SpanContext,
  name: String,
  kind: Span.Kind = Span.Kind.SERVER,
  toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
  attributes: Map[String, AttributeValue] = Map.empty
)(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]
```

Use it when you extract the context yourself. When you want the extraction done for you, use `Tracing#fromRootSpan`.

### Tracing#fromRootSpan

`Tracing#fromRootSpan` extracts a span context from a carrier and starts a span that continues the remote trace:

```scala
def fromRootSpan[C, R, E, E1 <: E, A](
  format: TextFormat,
  carrier: C,
  getter: TextFormat.Getter[C],
  name: String,
  kind: Span.Kind = Span.Kind.SERVER,
  toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
  attributes: Map[String, AttributeValue] = Map.empty
)(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]
```

If extraction throws, for example because the carrier holds no trace headers, the method falls back to `Tracing#root` with the same `name`, `kind`, and `toErrorStatus`, so the effect always runs inside a span. See [Propagating Context](#propagating-context) for a complete round trip.

### Aspects

The `aspects` object in the service offers `span`, `root`, `fromRemoteSpan`, and `fromRootSpan` as `ZIOAspect` values, so you can write `zio @@ span("name")` instead of nesting calls. Each takes the same arguments as the method of the same name, minus the effect, and has a type parameter `E` for the error type that the error mapper handles:

```scala
object aspects {
  def span[E](name: String, kind: Span.Kind = ..., toErrorStatus: ErrorMapper[E] = ..., attributes: Map[String, AttributeValue] = ...): ZIOAspect[...]
  def root[E](name: String, kind: Span.Kind = ..., toErrorStatus: ErrorMapper[E] = ..., attributes: Map[String, AttributeValue] = ...): ZIOAspect[...]
  def fromRemoteSpan[E](remote: SpanContext, name: String, ...): ZIOAspect[...]
  def fromRootSpan[C, E](format: TextFormat, carrier: C, getter: TextFormat.Getter[C], name: String, ...): ZIOAspect[...]
}
```

Import them from the service value to use them. When the effect can fail, give the error type explicitly, as in `span[Throwable]("name")`:

```scala mdoc:compile-only
val withAspects: Task[Unit] =
  withTracing { tracing =>
    import tracing.aspects._

    ZIO.attempt(()) @@ span[Throwable]("child") @@ root[Throwable]("parent")
  }
```

## Attributes

Attributes are key-value pairs on the current span.

### Tracing#putAttributes

`Tracing#putAttributes` adds attributes to the current span. It does nothing when no span is current:

```scala
def putAttributes(attrs: Map[String, AttributeValue])(implicit trace: Trace): UIO[Unit]
```

The values are OpenCensus `AttributeValue` objects. You can build them with the factory methods on `AttributeValue`, or with the implicit conversions described next:

```scala mdoc:compile-only
val explicit: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("work") {
      tracing.putAttributes(Map("user" -> AttributeValue.stringAttributeValue("alice")))
    }
  }
```

### Attribute Conversions

The `zio.telemetry.opencensus.implicits` object converts `Boolean`, `String`, `Long`, and `Double` values to `AttributeValue` implicitly, so a plain map literal can be used wherever an attribute map is expected. The object extends the trait `Attributes.implicits`, which you can also mix into your own scope:

```scala
object Attributes {
  trait implicits {
    implicit def boolToAttribute(b: Boolean): AttributeValue
    implicit def stringToAttribute(s: String): AttributeValue
    implicit def longToAttribute(l: Long): AttributeValue
    implicit def doubleToAttribute(d: Double): AttributeValue
  }
}
```

The following example passes plain values both as span attributes and to `Tracing#putAttributes`. Note that the map must have the expected type `Map[String, AttributeValue]` for the conversions to apply, and that `Int` values need an `L` suffix:

```scala mdoc:compile-only
import zio.telemetry.opencensus.implicits._

val converted: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("work", attributes = Map[String, AttributeValue]("env" -> "prod", "retries" -> 3L)) {
      tracing.putAttributes(Map[String, AttributeValue]("cached" -> true, "ratio" -> 0.5))
    }
  }
```

## Error Mapping

`ErrorMapper` tells a span which OpenCensus `Status` to record when the effect fails:

```scala
case class ErrorMapper[-E](body: PartialFunction[E, Status])

object ErrorMapper {
  val default: ErrorMapper[Any]
}
```

When an effect fails, the span status is `toErrorStatus.body` applied to the first typed failure. If the mapper is not defined for it, or the failure is a defect or an interruption with no typed failure, the status is `Status.UNKNOWN`. A successful effect leaves the status unchanged.

`ErrorMapper.default` is an empty partial function, so every failure is recorded as `Status.UNKNOWN`. The type parameter is contravariant, so the default can be passed where any `ErrorMapper[E]` is expected.

The following example maps one error type to a specific status and leaves the others as unknown:

```scala mdoc:compile-only
val mapper: ErrorMapper[Throwable] =
  ErrorMapper[Throwable] {
    case _: IllegalArgumentException => Status.INVALID_ARGUMENT
  }

val mapped: Task[Unit] =
  withTracing { tracing =>
    tracing.root("validate", toErrorStatus = mapper)(ZIO.fail(new IllegalArgumentException("bad input")))
  }
```

## Propagating Context

To continue a trace in another process, the current span context is injected into a carrier, the carrier travels with the request, and the receiving process extracts it and starts a span. `Tracing#inject` is the sending half, and `Tracing#fromRootSpan` or `Tracing#fromRemoteSpan` is the receiving half:

```scala
def inject[C](
  format: TextFormat,
  carrier: C,
  setter: TextFormat.Setter[C]
)(implicit trace: Trace): UIO[Unit]
```

The effect writes the current span context into `carrier` through `setter`, using the encoding of `format`. OpenCensus carriers are mutable, so injection and extraction are not referentially transparent.

The following example uses the B3 format and a mutable map as the carrier. The OpenCensus `TextFormat` comes from `io.opencensus.trace.Tracing`, which is why we renamed that object on import. In a real system the carrier would be, for instance, a set of HTTP headers:

```scala mdoc:compile-only
import io.opencensus.trace.propagation.TextFormat

import scala.collection.mutable

val setter: TextFormat.Setter[mutable.Map[String, String]] =
  new TextFormat.Setter[mutable.Map[String, String]] {
    override def put(carrier: mutable.Map[String, String], key: String, value: String): Unit =
      carrier.update(key, value)
  }

val getter: TextFormat.Getter[mutable.Map[String, String]] =
  new TextFormat.Getter[mutable.Map[String, String]] {
    override def get(carrier: mutable.Map[String, String], key: String): String =
      carrier.get(key).orNull
  }

val roundTrip: UIO[mutable.Map[String, String]] =
  withTracing { tracing =>
    import tracing.aspects._

    val format  = OcTracing.getPropagationComponent.getB3Format
    val carrier = mutable.Map.empty[String, String]

    for {
      _ <- tracing.root("client")(tracing.inject(format, carrier, setter))
      _ <- ZIO.unit @@ fromRootSpan(format, carrier, getter, "server")
    } yield carrier
  }
```

## Accessing the Current Span

`Tracing#getCurrentSpan` returns the OpenCensus `Span` that is current for the calling fiber:

```scala
def getCurrentSpan: UIO[Span]
```

Outside any span it returns the blank span, which ignores every call. Use it when you need an OpenCensus API that the service does not wrap, such as adding an annotation, and wrap any call that mutates the span in `ZIO.succeed`:

```scala mdoc:compile-only
import io.opencensus.trace.Annotation

val annotated: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("work") {
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.addAnnotation(Annotation.fromDescription("checkpoint"))))
    }
  }
```
