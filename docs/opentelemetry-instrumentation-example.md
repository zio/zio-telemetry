---
id: opentelemetry-instrumentation-example
title: "OpenTelemetry Automatic Instrumentation Example"
---

Firstly, start Jaeger by running the following command:
```bash
docker run --rm -it \
  -p 16686:16686 \
  -p 14250:14250 \
  jaegertracing/all-in-one:1.36
```

Then start the server application
```bash
sbt -J-Dotel.service.name=example-server \
    -J-Dotel.traces.exporter=jaeger \
    -J-Dotel.exporter.jaeger.endpoint=http://localhost:14250 \
    -J-Dotel.propagators=jaeger \
    -J-Dotel.traces.sampler=always_on \ 
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ServerApp"


```

and the client application which will send one request to the server application
```bash
sbt -J-Dotel.service.name=example-client \
    -J-Dotel.traces.exporter=jaeger \
    -J-Dotel.exporter.jaeger.endpoint=http://localhost:14250 \
    -J-Dotel.propagators=jaeger \
    -J-Dotel.traces.sampler=always_on \  
    "opentelemetryInstrumentationExample/runMain zio.telemetry.opentelemetry.instrumentation.example.ClientApp"
```

Head over to [http://localhost:16686/](http://localhost:16686/) to see the result.