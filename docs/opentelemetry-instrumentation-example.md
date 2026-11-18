---
id: opentelemetry-instrumentation-example
title: "OpenTelemetry Automatic Instrumentation Example"
---

You can find the source code [here](https://github.com/zio/zio-telemetry/tree/series/2.x/opentelemetry-instrumentation-example).

This example runs a server and a client with the OpenTelemetry Java agent attached. The agent instruments the HTTP calls, and the server uses the library's `OpenTelemetry.contextJVM` layer so that its spans share the context the agent manages.

First, start the observability backends ([Jaeger](https://www.jaegertracing.io/) and [Seq](https://datalust.co/seq)).

Start Jaeger by running the following command:
```bash
docker run --rm -it \
  -d \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 14250:14250 \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:1.47
```

To run Seq, you also need to specify an admin password (the user is `admin`):
```bash
PH=$(echo 'admin123' | docker run --rm -i datalust/seq config hash)

docker run \
  -d \
  --restart unless-stopped \
  -e ACCEPT_EULA=Y \
  -e SEQ_FIRSTRUN_ADMINPASSWORDHASH="$PH" \
  -p 80:80 \
  -p 5341:5341 \
  datalust/seq
```

After this, we can start the applications to generate some traces and logs.

First, download the OpenTelemetry Java agent JAR:
```bash
OTEL_AGENT_PATH=$(cs fetch --classpath "io.opentelemetry.javaagent:opentelemetry-javaagent:latest.release")
```

Then start the server application:
```bash
sbt -J-javaagent:$OTEL_AGENT_PATH \
    -J-Dotel.service.name=example-server \
    -J-Dotel.traces.sampler=always_on \
    -J-Dotel.traces.exporter=otlp \
    -J-Dotel.exporter.otlp.protocol=grpc \
    -J-Dotel.logs.exporter=otlp \
    -J-Dotel.exporter.otlp.logs.protocol="http/protobuf" \
    -J-Dotel.exporter.otlp.logs.endpoint="http://localhost:5341/ingest/otlp/v1/logs" \
    -J-Dotel.metrics.exporter=none \
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ServerApp"
```

And start the client application, which sends one request to the server application:
```bash
sbt -J-javaagent:$OTEL_AGENT_PATH \
    -J-Dotel.service.name=example-client \
    -J-Dotel.traces.sampler=always_on \
    -J-Dotel.traces.exporter=otlp \
    -J-Dotel.exporter.otlp.protocol=grpc \
    -J-Dotel.metrics.exporter=none \
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ClientApp"
```

Head over to [Jaeger UI](http://localhost:16686/) and [Seq UI](http://localhost:80/) to see the result.
