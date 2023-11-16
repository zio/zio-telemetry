---
id: opentelemetry-example
title: "OpenTelemetry Example"
---

You can find the source code [here](https://github.com/zio/zio-telemetry/tree/series/2.x/opentelemetry-example).

For an explanation in more detail, check the [OpenTracing Example](opentracing-example.md).

We're going to show an example of how to collect traces and logs. For this, we will use 
[Jaeger](https://www.jaegertracing.io/) and [Seq](https://datalust.co/seq).

Start Jaeger by running the following command:
```bash
docker run --rm -it \
  -d \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 4317:4317 \
  -p 16686:16686 \
  jaegertracing/all-in-one:1.47
```

To run Seq, you also need to specify an admin password:
```bash
PH=$(echo 'admin123' | docker run --rm -i datalust/seq config hash)

docker run \
  --name seq \
  -d \
  --restart unless-stopped \
  -e ACCEPT_EULA=Y \
  -e SEQ_FIRSTRUN_ADMINPASSWORDHASH="$PH" \
  -p 80:80 \
  -p 5341:5341 \
  datalust/seq
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
and head over to [Jaeger UI](http://localhost:16686/) and [Seq UI](http://localhost:80/) to see the result.