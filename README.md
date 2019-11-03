# ZIO telemetry

[![CircleCI][badge-ci]][link-ci]
[![Discord][badge-discord]][link-discord]

ZIO telemetry is a purely-functional, type-safe [OpenTracing][link-otr] client, allowing to use opentracing with ZIO fibers.

Opentracing is a standard and API for distributed tracing, i.e. collecting timings, and logs across process boundaries. Well known implementations are [Jaeger][jaeger] and [Zipkin][zipkin].   

To use ZIO telemetry, you will need a `Clock` and a `Telemetry` Service in you environment:

```scala
import io.opentracing.mock.MockTracer
import io.opentracing.propagation._
import zio._
import zio.clock.Clock
import zio.telemetry._

val tracer = new MockTracer
val managedEnvironment = 
  for {
    clockService     <- ZIO.environment[Clock].toManaged_
    telemetryService <- managed(tracer)
  } yield new Clock with Telemetry {
    override val clock: Clock.Service[Any]    = clockService.clock
    override def telemetry: Telemetry.Service = telemetryService
  }
```

After importing `import zio.telemetry._`, additional combinators on `ZIO`s are available to support starting child spans, tagging, logging and managing baggage. Alternatively, all operations are available on the `Telemetry` companion object. 

```scala
// start a new root span and set some bagage item
val zio = UIO.unit
             .setBaggage("foo", "bar")
             .root("root span")
          
// start a child of the current span, set a tag and log a message
val zio = UIO.unit
             .tag("http.status_code", 200)
             .log("doing some serious work here!")
             .span("child span")

// read back a baggage item using teh companion object instead of a combinator
val baggageIO = Telemetry.getBaggage("foo")
                          
```

To propagate contexts across process boundaries, extraction and injection can be used. The current span context is injected into a carrier, which is passed through some side channel to the next process. There it is injected back and a child span of it is started. For the example we use the standardized `TextMap` carrier. For details about extraction and injection, please refer to [Opentracing Documentation][otr-inject-extract]. 

Due to the use of the (mutable) opentracing carrier APIs, injection and extraction are not referentially transparent.

```scala
val extractTM: TextMap = ???
val zio2 = zio.spanFrom(Format.Builtin.TEXT_MAP, extractTM, "child of remote span")
```

```scala
for {
  injectTM: TextMap <- new TextMapAdapter(Map.empty.asJava)
  _ <- Telemetry.inject(Format.Builtin.TEXT_MAP, tm)
} yield injectTM
```

[badge-ci]: https://circleci.com/gh/zio/zio-telemetry/tree/master.svg?style=svg
[badge-discord]: https://img.shields.io/discord/629491597070827530?logo=discord 
[link-ci]: https://circleci.com/gh/zio/zio-telemetry/tree/master
[link-discord]: https://discord.gg/2ccFBr4
[link-ot]: https://opentelemetry.io/
[link-otr]: https://opentracing.io/
[otr-inject-extract]: https://opentracing.io/docs/overview/inject-extract/
[jaeger]: https://www.jaegertracing.io
[zipkin]: https://www.zipkin.io
