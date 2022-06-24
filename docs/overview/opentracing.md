---
id: overview_opentracing
title: "OpenTracing"
---

OpenTracing is a standard and API for distributed tracing, i.e. collecting timings,
and logs across process boundaries. Well known implementations are [Jaeger](https://www.jaegertracing.io)
and [Zipkin](https://www.zipkin.io).

## Installation

First, add the following dependency to your build.sbt:
```
"dev.zio" %% "zio-opentracing" % <version>
```

## Usage

To use ZIO Telemetry, you will need an `OpenTracing` service in your
environment:

```scala
import io.opentracing.mock.MockTracer
import io.opentracing.propagation._
import zio._
import zio.telemetry.opentracing._

val tracer = new MockTracer

val layer = OpenTracing.live(tracer)
```

After importing `import zio.telemetry.opentracing._`, additional combinators
on `ZIO`s are available to support starting child spans, tagging, logging and
managing baggage.

```scala
// start a new root span and set some baggage item
val zio = ZIO.unit
             .setBaggage("foo", "bar")
             .root("root span")
          
// start a child of the current span, set a tag and log a message
val zio = ZIO.unit
             .tag("http.status_code", 200)
             .log("doing some serious work here!")
             .span("child span")
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
val buffer = new TextMapAdapter(mutable.Map.empty.asJava)
for {
  _ <- zio.inject(Format.Builtin.TEXT_MAP, buffer)
  _ <- zio.spanFrom(Format.Builtin.TEXT_MAP, buffer, "child of remote span")
} yield buffer
```