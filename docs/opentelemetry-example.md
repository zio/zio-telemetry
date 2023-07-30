---
id: opentelemetry-example
title: "OpenTelemetry Example"
---

You can find the source code [here](https://github.com/zio/zio-telemetry/tree/series/2.x/opentelemetry-example).

For an explanation in more detail, check the [OpenTracing Example](opentracing-example.md).

Firstly, start Jaeger by running the following command:
```bash
docker run --rm -it \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 4317:4317 \
  -p 16686:16686 \
  jaegertracing/all-in-one:1.47
```

Then start the proxy application
```bash
sbt "opentelemetryExample/runMain zio.telemetry.opentelemetry.example.ProxyApp"
```
and the backend application

```bash
sbt "opentelemetryExample/runMain zio.telemetry.opentelemetry.example.BackendApp"
```
Now perform the following request:
```bash
curl -X GET http://localhost:8080/statuses
```
and head over to [http://localhost:16686/](http://localhost:16686/) to see the result.