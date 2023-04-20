---
id: opentracing
title: "OpenTracing"
---

OpenTracing is a standard and API for distributed tracing, i.e. collecting timings,
and logs across process boundaries. Well known implementations are [Jaeger](https://www.jaegertracing.io) and [Zipkin](https://www.zipkin.io).

## Installation

First, add the following dependency to your build.sbt:

```
"dev.zio" %% "zio-opentracing" % "<version>"
```

## Usage

To use ZIO Telemetry, you will need an `OpenTracing` service in your
environment. You also need to provide a `tracer` (for this example we use `JaegerTracer.live` from `opentracing-example` module) implementation:

```scala
import zio.telemetry.opentracing.OpenTracing
import zio.telemetry.opentracing.example.JaegerTracer
import zio._
import io.opentracing.tag.Tags

val app =
  ZIO.serviceWithZIO[OpenTracing] { tracing =>
    import tracing.aspects._

    (for {
      _       <- ZIO.unit @@ tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
      _       <- ZIO.unit @@ tag(Tags.HTTP_METHOD.getKey, "GET")
      _       <- ZIO.unit @@ setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
      message <- Console.readline
      _       <- ZIO.unit @@ log("Message has been read")
    } yield message) @@ root("/app")
  }.provide(OpenTracing.live, JaegerTracer.live("my-app"))
```

After importing `import tracing.aspects._`, additional `ZIOAspect` combinators
on `ZIO`s are available to support starting child spans, tagging, logging and
managing baggage.

```scala
ZIO.serviceWithZIO[OpenTracing] { tracing =>
  import tracing.aspects._
  
  // start a new root span and set some baggage item
  val zio1 = ZIO.unit @@ 
    setBaggage("foo", "bar") @@ 
    root("root span")

  // start a child of the current span, set a tag and log a message
  val zio2 = ZIO.unit @@ 
    tag("http.status_code", 200) @@ 
    log("doing some serious work here!") @@ 
    span("child span")
}
```

To propagate contexts across process boundaries, extraction and injection can be
used. The current span context is injected into a carrier, which is passed
through some side channel to the next process. There it is injected back and a
child span of it is started. For the example we use the standardized `TextMap`
carrier. For details about extraction and injection, please refer to 
[OpenTracing Documentation](https://opentracing.io/docs/overview/inject-extract/). 

Due to the use of the (mutable) OpenTracing carrier APIs, injection and extraction
are not referentially transparent.

```scala
ZIO.serviceWithZIO[OpenTracing] { tracing =>
  import tracing.aspects._
  
  val buffer = new TextMapAdapter(mutable.Map.empty.asJava)
  for {
    _ <- ZIO.unit @@ inject(Format.Builtin.TEXT_MAP, buffer)
    _ <- ZIO.unit @@ spanFrom(Format.Builtin.TEXT_MAP, buffer, "child of remote span")
  } yield buffer
}
```