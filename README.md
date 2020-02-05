# ZIO telemetry

[![CircleCI][Badge-Circle]][Link-Circle]
[![Releases][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshots][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]
[![Discord][Badge-Discord]][Link-Discord]

ZIO telemetry is a purely-functional, type-safe [OpenTracing][open-tracing] client.

### OpenTracing

OpenTracing is a standard and API for distributed tracing, i.e. collecting timings,
and logs across process boundaries. Well known implementations are [Jaeger][jaeger]
and [Zipkin][zipkin].

To use ZIO telemetry, you will need a `Clock` and a `OpenTelemetry` service in your
environment:

```scala
import io.opentracing.mock.MockTracer
import io.opentracing.propagation._
import zio._
import zio.clock.Clock
import zio.telemetry.opentracing._

val tracer = new MockTracer

val managedEnvironment = 
  for {
    clock_ <- ZIO.environment[Clock].toManaged_
    ot     <- managed(tracer)
  } yield new Clock with Telemetry {
    override val clock: Clock.Service[Any]    = clock_.clock
    override def telemetry: Telemetry.Service = ot.telemetry
  }
```

After importing `import zio.telemetry.opentracing._`, additional combinators
on `ZIO`s are available to support starting child spans, tagging, logging and
managing baggage.

```scala
// start a new root span and set some baggage item
val zio = UIO.unit
             .setBaggage("foo", "bar")
             .root("root span")
          
// start a child of the current span, set a tag and log a message
val zio = UIO.unit
             .tag("http.status_code", 200)
             .log("doing some serious work here!")
             .span("child span")
```

To propagate contexts across process boundaries, extraction and injection can be
used. The current span context is injected into a carrier, which is passed
through some side channel to the next process. There it is injected back and a
child span of it is started. For the example we use the standardized `TextMap`
carrier. For details about extraction and injection, please refer to 
[OpenTracing Documentation][otr-inject-extract]. 

Due to the use of the (mutable) OpenTracing carrier APIs, injection and extraction
are not referentially transparent.

```scala
val buffer = new TextMapAdapter(mutable.Map.empty.asJava)
for {
  _ <- zio.inject(Format.Builtin.TEXT_MAP, buffer)
  _ <- zio.spanFrom(Format.Builtin.TEXT_MAP, buffer, "child of remote span")
} yield buffer
```

### Example usage

Firstly, start [Jaeger][jaeger] by running following command:
```bash
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HTTP_PORT=9411 \
  -p 5775:5775/udp \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.6
``` 

To check if it's running properly visit [Jaeger UI](http://localhost:16686/).
More info can be found [here][jaeger-docker].

Our application contains two services:
 1. [proxy](./modules/example/src/main/scala/zio/telemetry/example/ProxyServer.scala) service
 2. [backend](./modules/example/src/main/scala/zio/telemetry/example/BackendServer.scala) service

#### Proxy service

Represents entry point to example distributed system. It exposes `/statuses` endpoint which returns list of system's services statuses.

In order to start service run:
```bash
sbt "example/runMain zio.telemetry.example.ProxyServer"
```

If it's start properly it should be available on `http://0.0.0.0:8080/statuses`.


#### Backend service

Represents "internal" service of the system. It exposes `/status` endpoint which returns service status.

In order to start service run:
```bash
sbt "example/runMain zio.telemetry.example.BackendServer"
```

If it's start properly it should be available on `http://0.0.0.0:9000/status`.

Configuration is given in [application.conf](./modules/example/src/main/resources/application.conf).

After both services are properly started, running following command
```bash
curl -X GET http://0.0.0.0:8080/statuses
```
should return following response:
```json
{
  "data": [
    {
      "name": "backend",
      "version": "1.0.0",
      "status": "up"
    },
    {
      "name": "proxy",
      "version": "1.0.0",
      "status": "up"
    }
  ]
}
```

Simultaneously, it will create trace that will be stored in Jaeger backend.

[open-tracing]: https://opentracing.io/
[otr-inject-extract]: https://opentracing.io/docs/overview/inject-extract/
[jaeger]: https://www.jaegertracing.io
[zipkin]: https://www.zipkin.io
[jaeger-docker]: https://www.jaegertracing.io/docs/1.6/getting-started/#all-in-one-docker-image
[Badge-Circle]: https://circleci.com/gh/zio/interop-monix/tree/master.svg?style=svg
[Badge-Discord]: https://img.shields.io/discord/629491597070827530?logo=discord 
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.zio/zio-interop-monix_2.12.svg "Sonatype Releases"
[Badge-SonatypeSnapshots]: https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.zio/zio-interop-monix_2.12.svg "Sonatype Snapshots"
[Link-Discord]: https://discord.gg/2ccFBr4
[Link-Circle]: https://circleci.com/gh/zio/interop-monix/tree/master
[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/dev/zio/zio-interop-monix_2.12/ "Sonatype Releases"
[Link-SonatypeSnapshots]: https://oss.sonatype.org/content/repositories/snapshots/dev/zio/zio-interop-monix_2.12/ "Sonatype Snapshots"
