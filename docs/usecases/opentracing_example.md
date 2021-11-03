---
id: usecases_opentracing
title: "OpenTracing Example"
---

You can find the source code [here](https://github.com/zio/zio-telemetry/tree/master/opentracing-example).

Firstly, start [Jaeger](https://www.jaegertracing.io) by running the following command:
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
More info can be found [here](https://www.jaegertracing.io/docs/1.6/getting-started/#all-in-one-docker-image).

Our application contains two services:
 1. [Proxy](https://github.com/zio/zio-telemetry/blob/master/opentracing-example/src/main/scala/zio/telemetry/opentracing/example/ProxyServer.scala) service
 2. [Backend](https://github.com/zio/zio-telemetry/blob/master/opentracing-example/src/main/scala/zio/telemetry/opentracing/example/BackendServer.scala) service

### Proxy Service

Represents the entry point of the distributed system example. It exposes the `/statuses` endpoint which returns a list of system's services statuses.

The service consists of `ProxyServer` and `ProxyApp`.

#### ProxyServer

In order to start the service run:
```bash
sbt "opentracingExample/runMain zio.telemetry.opentracing.example.ProxyServer"
```

The console should output
```bash
ProxyServer started on port 8080
```
if the server has been started properly.

#### ProxyApp

Provides the implementation of the service, which returns the status of the backend service and the proxy service itself. `Client` is used to retrieve the status of the backend service.

This is also where the tracing of the application is done, by collecting the timings and logging things such as the span type and the HTTP method. The context is injected into a carrier, and passed along to the backend through `Client`, where a child span is created, and logging of the backend service is done.

### Backend Service

Represents the "internal" service of the system. It exposes the `/status` endpoint which returns the status of the backend service.

The service consists of `BackendServer` and `BackendApp`.

#### BackendServer

In order to start the service run:
```bash
sbt "opentracingExample/runMain zio.telemetry.opentracing.example.BackendServer"
```

The console should output
```bash
BackendServer started on port 9000
```
if the server has been started properly.

#### BackendApp

Provides the implementation of the service, which is to simply return the status of the backend service.

### Status

```scala
final case class Status(name: String, status: String)
```

Represents the status of a service.

### Statuses

```scala
final case class Statuses(data: List[Status]) extends AnyVal
```

Represents the statuses of a number of services.

### Configuration

Configuration is given in [application.conf](https://github.com/zio/zio-telemetry/blob/82787facf973feeb9c128f21a964fad15d7c591d/opentracing-example/src/main/resources/application.conf).

### Running

After both services are properly started, running the following command
```bash
curl -X GET http://localhost:8080/statuses
```
should return the following response:
```json
{"data":[{"name":"backend","status":"up"},{"name":"proxy","status":"up"}]}
```

Simultaneously, it will create trace that will be stored in Jaeger backend.