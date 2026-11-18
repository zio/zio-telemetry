---
id: opentracing
title: "OpenTracing"
---

:::warning
The OpenTracing project is deprecated upstream and superseded by OpenTelemetry. The `zio-opentracing` module exists for applications that already use it. For new code, use the [OpenTelemetry module](opentelemetry.md), and see [How to Migrate from OpenTracing or OpenCensus](how-to/migrate-from-opentracing-or-opencensus.md) to move an existing application.
:::

The `zio-opentracing` module wraps the [OpenTracing](https://opentracing.io/) API in a ZIO service named `OpenTracing`. Each effect that runs inside a span records its timing, tags, logs, and errors on the OpenTracing `Tracer` that you provide, for example the Jaeger or Zipkin tracer. This page lists every public member of the module.

## Installation

Add the module to your `build.sbt`. It depends on `opentracing-api` and `opentracing-noop` and brings both transitively, but you must add the tracer implementation you want to use yourself:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenTracing (JVM, Scala 2.12, 2.13, 3)
  "dev.zio" %% "zio-opentracing" % "@VERSION@"
)
```

## Creating the Service

The `OpenTracing` trait is the service. It is created by one of the constructors in the `OpenTracing` companion object, each of which needs an OpenTracing `Tracer`.

### OpenTracing.live

`OpenTracing.live` builds the service as a layer from a `Tracer` in the environment:

```scala
def live(rootOperation: String = "ROOT"): URLayer[Tracer, OpenTracing]
```

Note that the method has a default argument, so you must call it with parentheses, `OpenTracing.live()`, even when you keep the default. The layer starts one span named `rootOperation` when the layer is built. That span is the initial current span, and it is the parent of every span you start with `OpenTracing#span`. The span is finished when the layer's scope closes.

The following example builds the service from a `Tracer` layer. The example uses the no-op tracer from `opentracing-noop`, so it needs no tracing backend. Replace it with your own tracer in a real application:

```scala mdoc:silent
import io.opentracing.Tracer
import io.opentracing.noop.NoopTracerFactory
import zio._
import zio.telemetry.opentracing.OpenTracing

val tracerLayer: ULayer[Tracer] =
  ZLayer.succeed(NoopTracerFactory.create())

val tracingLayer: ULayer[OpenTracing] =
  tracerLayer >>> OpenTracing.live("my-app")
```

### OpenTracing.scoped

`OpenTracing.scoped` builds the service as a scoped effect from an explicit tracer instead of a layer:

```scala
def scoped(tracer: Tracer, rootOperation: String): URIO[Scope, OpenTracing]
```

It is what `OpenTracing.live` uses internally. The root span is finished when the `Scope` is closed. Use it when you already hold a `Tracer` value, for example in a test:

```scala mdoc:silent
val scopedTracing: URIO[Scope, OpenTracing] =
  OpenTracing.scoped(NoopTracerFactory.create(), "my-app")
```

### OpenTracing.noop

`OpenTracing.noop` is a ready-made layer backed by the no-op tracer. It performs no recording, which makes it useful in tests and in code paths where tracing is switched off:

```scala mdoc:silent
val noopLayer: ULayer[OpenTracing] = OpenTracing.noop
```

## Spans

The methods in this section start spans. All of them take the operation name and the two flags `tagError` and `logError`, both `true` by default. When the wrapped effect fails, the span is tagged with `error=true` if `tagError` is set, and a log entry with the fields `error.object` and `stack` is added if `logError` is set. The span is finished, and the previous current span is restored, whether the effect succeeds, fails, or is interrupted. The failure itself is not handled; it is passed on unchanged.

The examples on this page share the following setup, which makes the `OpenTracing` service available through an environment parameter:

```scala mdoc:silent
def withTracing[R, E, A](f: OpenTracing => ZIO[R, E, A]): ZIO[R, E, A] =
  ZIO.serviceWithZIO[OpenTracing](f).provideSomeLayer[R](OpenTracing.noop)
```

### OpenTracing#root

`OpenTracing#root` starts a new span that has no parent and makes it the current span for the duration of `zio`. Use it at the entry point of a request:

```scala
def root[R, E, A](
  operation: String,
  tagError: Boolean = true,
  logError: Boolean = true
)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
```

For example, we wrap a whole request handler in a root span:

```scala mdoc:compile-only
val handler: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("handle-request")(ZIO.unit)
  }
```

### OpenTracing#span

`OpenTracing#span` starts a child of the current span and makes it the current span for the duration of `zio`:

```scala
def span[R, E, A](
  operation: String,
  tagError: Boolean = true,
  logError: Boolean = true
)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
```

The following example starts a child span inside a root span:

```scala mdoc:compile-only
val nested: UIO[Unit] =
  withTracing { tracing =>
    tracing.root("handle-request") {
      tracing.span("load-user")(ZIO.unit)
    }
  }
```

### OpenTracing#spanFrom

`OpenTracing#spanFrom` extracts a span context from a carrier and starts a child of that context. This is how a service continues a trace that was started in another process:

```scala
def spanFrom[R, E, S, C](
  format: Format[C],
  carrier: C,
  operation: String,
  tagError: Boolean = true,
  logError: Boolean = true
)(zio: => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S]
```

If the tracer throws while extracting, no span is started and `zio` runs unchanged. See [Propagating Context](#propagating-context) for a complete round trip.

### Aspects

The `aspects` object in the service offers `root`, `span`, and `spanFrom` as `ZIOAspect` values, so you can write `zio @@ span("name")` instead of nesting calls. They take the same arguments as the methods above, minus the effect, and delegate to them:

```scala
object aspects {
  def root(operation: String, tagError: Boolean = true, logError: Boolean = true): ZIOAspect[...]
  def span(operation: String, tagError: Boolean = true, logError: Boolean = true): ZIOAspect[...]
  def spanFrom[C](
    format: Format[C],
    carrier: C,
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIOAspect[...]
}
```

Import them from the service value to use them:

```scala mdoc:compile-only
val withAspects: UIO[Unit] =
  withTracing { tracing =>
    import tracing.aspects._

    ZIO.unit @@ span("child") @@ root("parent")
  }
```

## Tags, Logs, and Baggage

These methods act on the current span, which is the span started by the innermost enclosing `root`, `span`, or `spanFrom`, or the service's root span if there is none.

### OpenTracing#tag

`OpenTracing#tag` sets a tag on the current span. It is overloaded for the three value types that OpenTracing supports:

```scala
def tag(key: String, value: String)(implicit trace: Trace): UIO[Unit]
def tag(key: String, value: Int)(implicit trace: Trace): UIO[Unit]
def tag(key: String, value: Boolean)(implicit trace: Trace): UIO[Unit]
```

The following example sets one tag of each type:

```scala mdoc:compile-only
val tagged: UIO[Unit] =
  withTracing { tracing =>
    tracing.span("call-backend") {
      tracing.tag("http.method", "GET") *>
        tracing.tag("http.status_code", 200) *>
        tracing.tag("cache.hit", false)
    }
  }
```

### OpenTracing#log

`OpenTracing#log` adds a log entry to the current span. It is stamped with the current time of the ZIO `Clock`. The first overload records a message; the second records structured fields:

```scala
def log(msg: String)(implicit trace: Trace): UIO[Unit]
def log(fields: Map[String, ?])(implicit trace: Trace): UIO[Unit]
```

The following example uses both overloads:

```scala mdoc:compile-only
val logged: UIO[Unit] =
  withTracing { tracing =>
    tracing.span("work") {
      tracing.log("starting") *> tracing.log(Map("event" -> "retry", "attempt" -> 2))
    }
  }
```

### OpenTracing#error

`OpenTracing#error` records a failure on a span you name. The span methods call it for you when the wrapped effect fails, so you only need it to report an error on a span yourself:

```scala
def error(
  span: Span,
  cause: Cause[?],
  tagError: Boolean = true,
  logError: Boolean = true
)(implicit trace: Trace): UIO[Unit]
```

With `tagError` it sets the tag `error=true`. With `logError` it logs the fields `error.object`, which is the first failure if it is a `Throwable` and a `FiberFailure` wrapping the cause otherwise, and `stack`, which is the pretty-printed cause.

### OpenTracing#finish

`OpenTracing#finish` finishes a span at the current time of the ZIO `Clock`. The span methods finish their spans automatically, so it is only needed for spans you obtained with `OpenTracing#getCurrentSpanUnsafe` or built directly with the tracer:

```scala
def finish(span: Span)(implicit trace: Trace): UIO[Unit]
```

### OpenTracing#setBaggageItem and OpenTracing#getBaggageItem

Baggage items are key-value pairs that travel with the span context to every child span, including spans in other processes after injection. `OpenTracing#setBaggageItem` sets an item on the current span and `OpenTracing#getBaggageItem` reads it back as an `Option`:

```scala
def setBaggageItem(key: String, value: String)(implicit trace: Trace): UIO[Unit]
def getBaggageItem(key: String)(implicit trace: Trace): UIO[Option[String]]
```

The following example sets an item on a parent span and reads it from a child span:

```scala mdoc:compile-only
val baggage: UIO[Option[String]] =
  withTracing { tracing =>
    tracing.root("parent") {
      tracing.setBaggageItem("user-id", "42") *>
        tracing.span("child")(tracing.getBaggageItem("user-id"))
    }
  }
```

## Accessing the Current Span

Two methods return the OpenTracing objects for the current span:

```scala
def getCurrentSpanUnsafe(implicit trace: Trace): UIO[Span]
def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext]
```

They are named `Unsafe` because they hand out the mutable OpenTracing `Span`, whose methods are not effects. Use them when you need an OpenTracing API that the service does not wrap, and wrap any call that mutates the span in `ZIO.succeed`.

## Propagating Context

To continue a trace in another process, the current span context is injected into a carrier, the carrier travels with the request, and the receiving process extracts it and starts a child span. `OpenTracing#inject` is the sending half, and `OpenTracing#spanFrom` is the receiving half:

```scala
def inject[C](format: Format[C], carrier: C)(implicit trace: Trace): UIO[Unit]
```

The effect writes the current span context into `carrier` using the tracer's codec for `format`. OpenTracing carriers are mutable, so injection and extraction are not referentially transparent. The following example injects into a `TextMapAdapter` and continues the trace from the same carrier. In a real system the carrier would be, for instance, a set of HTTP headers:

```scala mdoc:compile-only
import io.opentracing.propagation.{Format, TextMapAdapter}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

val roundTrip: UIO[mutable.Map[String, String]] =
  withTracing { tracing =>
    import tracing.aspects._

    val headers = mutable.Map.empty[String, String]
    val carrier = new TextMapAdapter(headers.asJava)

    for {
      _ <- tracing.root("client")(tracing.inject(Format.Builtin.TEXT_MAP, carrier))
      _ <- ZIO.unit @@ spanFrom(Format.Builtin.TEXT_MAP, carrier, "server")
    } yield headers
  }
```

For the available formats and what each carries, see the [OpenTracing documentation on inject and extract](https://opentracing.io/docs/overview/inject-extract/).

## A Complete Program

The following program combines the pieces: a root span for the request, a child span with tags and a log entry, and the layer wiring. It uses the no-op tracer, so it prints nothing and needs no backend:

```scala mdoc:compile-only
import io.opentracing.tag.Tags

object TracedApp extends ZIOAppDefault {

  val request: ZIO[OpenTracing, Nothing, Unit] =
    ZIO.serviceWithZIO[OpenTracing] { tracing =>
      import tracing.aspects._

      (for {
        _ <- tracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
        _ <- tracing.tag(Tags.HTTP_METHOD.getKey, "GET")
        _ <- tracing.setBaggageItem("request-id", "abc")
        _ <- tracing.log("request started")
      } yield ()) @@ span("call-backend") @@ root("/app")
    }

  def run: UIO[Unit] =
    request.provide(OpenTracing.noop)
}
```

To send real data, replace `OpenTracing.noop` with `ZLayer.succeed(yourTracer) >>> OpenTracing.live("my-app")`, where `yourTracer` is a Jaeger or Zipkin `io.opentracing.Tracer`. A full application with a Jaeger tracer is described in [OpenTracing Example](opentracing-example.md).
