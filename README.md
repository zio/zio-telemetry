# ZIO telemetry

[![Project stage][Stage]][Stage-Page]
![CI][Badge-CI]
[![Releases][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshots][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]
[![Discord][Badge-Discord]][Link-Discord]

ZIO telemetry is purely-functional and type-safe. It provides clients for [OpenTracing][open-tracing] and [OpenTelemetry][open-telemetry]

### OpenTracing

OpenTracing is a standard and API for distributed tracing, i.e. collecting timings,
and logs across process boundaries. Well known implementations are [Jaeger][jaeger]
and [Zipkin][zipkin].

First, add the following dependency to your build.sbt:
```
"dev.zio" %% "zio-opentracing" % <version>
```

To use ZIO telemetry, you will need a `Clock` and a `OpenTelemetry` service in your
environment:

```scala
import io.opentracing.mock.MockTracer
import io.opentracing.propagation._
import zio._
import zio.clock.Clock
import zio.opentracing._

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

After importing `import zio.opentracing._`, additional combinators
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
 1. proxy service
 2. backend service

#### Proxy service

Represents entry point to example distributed system. It exposes `/statuses` endpoint which returns list of system's services statuses.

The service consists of a `ProxyServer` and `StatusesService`.

##### ProxyServer

In order to start service run:
```bash
sbt "opentracingExample/runMain zio.telemetry.opentracing.example.ProxyServer"
```

The console should output
```bash
ProxyServer started on port 8080
```
if the server has been started properly.

##### StatusesService

Provides the implementation of the service, which returns the status of the backend service and the proxy service itself. `Client` is used to retrieve the status of the backend service.

#### Backend service

Represents "internal" service of the system. It exposes `/status` endpoint which returns the status of the backend service.

The service consists of a `BackendServer` and `StatusService`.

##### BackendServer

In order to start service run:
```bash
sbt "opentracingExample/runMain zio.telemetry.opentracing.example.BackendServer"
```

The console should output
```bash
BackendServer started on port 9000
```
if the server has been started properly.

##### StatusService

Provides the implementation of the service, which is to simply return the status of the backend service.

#### Status

```scala
final case class Status(name: String, status: String)
```

Represents the status of a service.

#### Statuses

```scala
final case class Statuses(data: List[Status]) extends AnyVal
```

Represents the statuses of a number of services.

#### Configuration

Configuration is given in `application.conf`.

#### Running

After both services are properly started, running following command
```bash
curl -X GET http://localhost:8080/statuses
```
should return following response:
```json
{"data":[{"name":"backend","status":"up"},{"name":"proxy","status":"up"}]}
```

Simultaneously, it will create trace that will be stored in Jaeger backend.

### OpenTelemetry

Use this dependency instead:
```
"dev.zio" %% "zio-opentelemetry" % <version>
```

First, start Jaeger by running
```bash
docker run --rm -it \
  -p 16686:16686 \
  -p 14250:14250 \
  jaegertracing/all-in-one:1.16
```

Then start the proxy server
```bash
sbt "opentelemetryExample/runMain zio.telemetry.opentelemetry.example.ProxyServer"
```
and the backend server

```bash
sbt "opentelemetryExample/runMain zio.telemetry.opentelemetry.example.BackendServer"
```
Now perform the following request:
```bash
curl -X GET http://localhost:8080/statuses
```
and head over to [http://localhost:16686/](http://localhost:16686/) to see the result.

[open-tracing]: https://opentracing.io/
[open-telemetry]: https://opentelemetry.io/
[otr-inject-extract]: https://opentracing.io/docs/overview/inject-extract/
[jaeger]: https://www.jaegertracing.io
[zipkin]: https://www.zipkin.io
[jaeger-docker]: https://www.jaegertracing.io/docs/1.6/getting-started/#all-in-one-docker-image
[Badge-CI]: https://github.com/zio/zio-telemetry/workflows/CI/badge.svg
[Badge-Discord]: https://img.shields.io/discord/629491597070827530?logo=discord 
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.zio/zio-opentracing_2.12.svg "Sonatype Releases"
[Badge-SonatypeSnapshots]: https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.zio/zio-opentracing_2.12.svg "Sonatype Snapshots"
[Link-Discord]: https://discord.gg/2ccFBr4
[Link-Circle]: https://circleci.com/gh/zio/zio-telemetry/tree/master
[Link-SonatypeReleases]: https://oss.sonatype.org/content/repositories/releases/dev/zio/zio-opentracing_2.12/ "Sonatype Releases"
[Link-SonatypeSnapshots]: https://oss.sonatype.org/content/repositories/snapshots/dev/zio/zio-opentracing_2.12/ "Sonatype Snapshots"
[Stage]: https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg
[Stage-Page]: https://github.com/zio/zio/wiki/Project-Stages
