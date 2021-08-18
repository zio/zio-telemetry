---
id: usecases_opentelemetry
title: "OpenTelemetry Example"
---

You can find the source code [here](https://github.com/zio/zio-telemetry/tree/master/opentracing-example).

For an explanation in more detail, check the [OpenTracing Example](opentracing_example.md).

Firstly, start Jaeger by running the following command:
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