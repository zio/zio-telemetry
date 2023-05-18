---
id: opentelemetry-instrumentation-example
title: "OpenTelemetry Automatic Instrumentation Example"
---

Firstly, download OpenTelemetry JVM agent JAR:
```bash
OTEL_AGENT_PATH=$(cs fetch --classpath "io.opentelemetry.javaagent:opentelemetry-javaagent:latest.release")
 ```

Then start Jaeger by running the following command:
```bash
docker run --rm -it \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:1.42
 ```

Then start the server application
```bash
sbt -J-javaagent:$OTEL_AGENT_PATH \
    -J-Dotel.service.name=example-server \
    -J-Dotel.traces.sampler=always_on \
    -J-Dotel.traces.exporter=otlp \
    -J-Dotel.metrics.exporter=none \
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ServerApp"
 ```

and the client application which will send one request to the server application
```bash
sbt -J-javaagent:$OTEL_AGENT_PATH \
    -J-Dotel.service.name=example-client \
    -J-Dotel.traces.sampler=always_on \
    -J-Dotel.traces.exporter=otlp \
    -J-Dotel.metrics.exporter=none \
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ClientApp"
 ```

Head over to [http://localhost:16686/](http://localhost:16686/) to see the result.
