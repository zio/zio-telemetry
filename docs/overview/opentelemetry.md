---
id: overview_opentelemetry
title: "OpenTelemetry"
---

OpenTelemetry is a collection of tools, APIs, and SDKs. You can use it to instrument, generate, collect, and export telemetry data for analysis in order to understand your software's performance and behavior. Well known implementations are [Jaeger](https://www.jaegertracing.io)
and [Zipkin](https://www.zipkin.io).

## Installation

First, add the following dependency to your build.sbt:
```
"dev.zio" %% "zio-opentelemetry" % <version>
```

## Usage

To use ZIO Telemetry, you will need a `Clock` and a `Tracing` service in your environment. You also need to provide a `tracer` implementation:

```scala
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import zio._
import zio.console.getStrLn
import zio.clock.Clock
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.Tracing.root

val errorMapper = { case _ => StatusCode.UNSET }

val app = 
  //start root span that lasts until the effect finishes
  root("root span", SpanKind.INTERNAL, errorMapper) {
    for {
      //sets an attribute to the current span
      _ <- Tracing.setAttribute("foo", "bar")
      //adds an event to the current span
      _       <- Tracing.addEvent("foo")
      message <- getStrLn
      _       <- Tracing.addEvent("bar")
    } yield message
  }.provideLayer(tracer ++ Clock.live >>> Tracing.live)
```

After importing `import zio.telemetry.opentelemetry._`, additional combinators
on `ZIO`s are available to support starting child spans, adding events and setting attributes.

```scala
// start a new root span and set some attribute
val zio = UIO.unit
             .setAttribute("foo", "bar")
             .root("root span")
          
// start a child of the current span, set an attribute and add an event
val zio = UIO.unit
             .setAttribute("http.status_code", 200)
             .addEvent("doing some serious work here!")
             .span("child span")
```

To propagate contexts across process boundaries, extraction and injection can be
used. The current span context is injected into a carrier, which is passed
through some side channel to the next process. There it is injected back and a
child span of it is started.

Due to the use of the (mutable) OpenTelemetry carrier APIs, injection and extraction
are not referentially transparent.

```scala
val propagator                           = W3CTraceContextPropagator.getInstance()
val carrier: mutable.Map[String, String] = mutable.Map().empty

val getter: TextMapGetter[mutable.Map[String, String]] = new TextMapGetter[mutable.Map[String, String]] {
  override def keys(carrier: mutable.Map[String, String]): lang.Iterable[String] =
    carrier.keys.asJava

  override def get(carrier: mutable.Map[String, String], key: String): String =
    carrier.get(key).orNull
}

val setter: TextMapSetter[mutable.Map[String, String]] =
  (carrier, key, value) => carrier.update(key, value)

val injectExtract =
  inject(
    propagator,
    carrier,
    setter
  ).span("foo") *> UIO.unit
    .spanFrom(propagator, carrier, getter, "baz")
    .span("bar")
```