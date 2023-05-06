---
id: opentelemetry
title: "OpenTelemetry"
---

OpenTelemetry is a collection of tools, APIs, and SDKs. You can use it to instrument, generate, collect, and export telemetry data for analysis in order to understand your software's performance and behavior. Well known implementations are [Jaeger](https://www.jaegertracing.io)
and [Zipkin](https://www.zipkin.io).

## Installation

First, add the following dependency to your build.sbt:
```
"dev.zio" %% "zio-opentelemetry" % "<version>"
```

## Usage

### Tracing

To use ZIO Telemetry, you will need a `Tracing` service in your environment. You also need to provide a `Tracer`
(for this example we use `JaegerTracer.live` from `opentelemetry-example` module) and `ContextStorage` implementation.

```scala
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.example.JaegerTracer
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import zio._

val errorMapper = ErrorMapper[Throwable]{ case _ => StatusCode.UNSET }

val app =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    // import available aspects to create spans conveniently
    import tracing.aspects._

    val zio = for {
      // set an attribute to the current span
      _       <- tracing.setAttribute("zio", "telemetry")
      // add an event to the current span
      _       <- tracing.addEvent("before readline")
      // some logic
      message <- Console.readline
      // add another event to the current span
      _       <- tracing.addEvent("after readline")
    } yield message
    
    // create a root span out of `zio`
    zio @@ root("root span", SpanKind.INTERNAL, errorMapper)
    
  }.provide(Tracing.live, ContextStorage.fiberRef, JaegerTracer.live)
```

### Baggage

To use Baggage API, you also will need a `Baggage` service in your environment. You also need to provide 
`ContextStorage` implementation.

```scala
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.ContextStorage
import zio._

 val app = 
  ZIO.serviceWithZIO[Baggage] { baggage => 
    val carrier = OutgoingContextCarrier.default()
  
    for {
      // add new key/value into the baggage of current tracing context
      _ <- baggage.set("zio", "telemetry")
      // import current baggage data into carrier so it can be used by downstream consumer
      _ <- baggage.inject(BaggagePropagator.default, carrier)
    } yield ()
    
    for {
      // extract current baggage data from the carrier
      _    <- baggage.extract(BaggagePropagator.default, IncomingContextCarrier.default(carrier.kernel))  
      // get value from the extracted baggage
      data <- baggage.get("zio")
    } yield data
  }.provide(Baggage.live, ContextStorage.fiberRef)
```

### Context Propagation

To propagate contexts across process boundaries, extraction and injection can be
used. The current span context is injected into a carrier, which is passed
through some side channel to the next process. There it is extracted back and a
child span of it is started.

Due to the use of the (mutable) OpenTelemetry carrier APIs, injection and extraction
are not referentially transparent.

```scala
ZIO.serviceWithZIO[Tracing] { tracing =>
  import tracing.aspects._
  
  val propagator = TraceContextPropagator.default
  val kernel     = mutable.Map().empty
  
  tracing.inject(propagator, OutgoingContextCarrier.default(kernel)) @@ root("span of upstream service") *>
    extractSpan(propagator, IncomingContextCarrier.default(kernel), "span of downstream service")
}
```

### Usage with OpenTelemetry automatic instrumentation

OpenTelemetry provides
a [JVM agent for automatic instrumentation](https://opentelemetry.io/docs/instrumentation/java/automatic/) which
supports
many [popular Java libraries](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md).

Since [version 1.25.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.25.0)
OpenTelemetry JVM agent supports ZIO.

To enable interoperability between automatic instrumentation and `zio-opentelemetry`, `Tracing` has to be created
using `ContextStorage` backed by OpenTelemetry's `Context`.

```scala
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.example.JaegerTracer
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio._

val errorMapper = ErrorMapper[Throwable] { case _ => StatusCode.UNSET }

val app =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    import tracing.aspects._
    ZIO.logInfo("Hello") @@ root("root span", SpanKind.INTERNAL, errorMapper)
  }.provide(
    Tracing.live,
    ContextStorage.openTelemetryContext, // <<<
    JaegerTracer.live
  )
```
