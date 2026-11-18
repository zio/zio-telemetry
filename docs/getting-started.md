---
id: getting-started
title: "Getting Started"
---

In this guide we trace a small ZIO program with OpenTelemetry and print the resulting spans to the console as OTLP JSON. It needs no collector or backend, so it is the quickest way to see ZIO Telemetry work.

## Adding Dependencies

First, add the ZIO Telemetry artifact for OpenTelemetry to your build. The [Installation](index.md#installation) section of the introduction lists every artifact. Besides `zio-opentelemetry`, this guide needs the OpenTelemetry SDK and its logging exporter, because the library itself only depends on the OpenTelemetry API:

```scala
libraryDependencies ++= Seq(
  // ZIO Telemetry for OpenTelemetry
  "dev.zio"         %% "zio-opentelemetry"                % "@VERSION@",
  // The OpenTelemetry SDK that creates real spans
  "io.opentelemetry" % "opentelemetry-sdk"                % "1.68.0",
  // An exporter that prints OTLP JSON to standard output
  "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % "1.68.0"
)
```

## Creating the OpenTelemetry Layer

The `OpenTelemetry.custom` layer takes a scoped effect that builds an `io.opentelemetry.api.OpenTelemetry` instance. Because the effect is scoped, ZIO closes the SDK when the application shuts down and flushes any pending spans.

In the following code we create a tracer provider whose `SimpleSpanProcessor` hands every finished span to `OtlpJsonLoggingSpanExporter`, which prints it to stdout:

```scala mdoc:silent
import zio._
import io.opentelemetry.api
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio.telemetry.opentelemetry.OpenTelemetry

def otelSdkLayer: TaskLayer[api.OpenTelemetry] =
  OpenTelemetry.custom(
    for {
      spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
      tracerProvider <- ZIO.fromAutoCloseable(
                          ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
                        )
      sdk            <- ZIO.fromAutoCloseable(
                          ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
                        )
    } yield sdk
  )
```

## Writing the Program

The `Tracing` service is the API for creating spans. We access it from the environment and use `Tracing#root` to start a trace and `Tracing#span` to start a child span inside it. Each span lasts exactly as long as the effect we pass to it. Inside the spans we use `Tracing#setAttribute` and `Tracing#addEvent` to enrich the current span.

The program below is only a `def`, so nothing runs when this page is built:

```scala mdoc:silent
import zio.telemetry.opentelemetry.tracing.Tracing

def program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.root("checkout") {
      for {
        _ <- tracing.setAttribute("customer.id", "c-42")
        _ <- tracing.span("charge-card") {
               for {
                 _ <- tracing.setAttribute("payment.amount", 19.99)
                 _ <- tracing.addEvent("card charged")
               } yield ()
             }
        _ <- tracing.addEvent("order completed")
      } yield ()
    }
  }
```

The `charge-card` span is a child of `checkout`, because `Tracing#span` uses the span that is current in the fiber as its parent.

## Wiring the Layers

To run the program we provide three things. `otelSdkLayer` is the OpenTelemetry instance, `OpenTelemetry.tracing` turns it into a `Tracing` service under the given instrumentation scope name, and `OpenTelemetry.contextZIO` supplies the storage that keeps the current span in a `FiberRef`. See [Context and Fibers](concepts/context-and-fibers.md) for why that storage exists:

```scala mdoc:compile-only
object GettingStartedApp extends ZIOAppDefault {

  override def run =
    program.provide(
      otelSdkLayer,
      OpenTelemetry.tracing("dev.zio.GettingStartedApp"),
      OpenTelemetry.contextZIO
    )

}
```

## Running the Application

Put the code from the previous sections in one file with the usual imports. With sbt, run the main class using `sbt run`. With [Scala CLI](https://scala-cli.virtuslab.org/), declare the dependencies in the file itself and run `scala-cli GettingStartedApp.scala`:

```scala
//> using scala "2.13"
//> using dep dev.zio::zio-opentelemetry:@VERSION@
//> using dep io.opentelemetry:opentelemetry-sdk:1.68.0
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.68.0
```

The application prints one JSON document per finished span. The `charge-card` span appears first, because it ends first, and carries the same trace ID as `checkout`, with `checkout` as its parent span. The attributes and events we added appear inside the span they were recorded on.

## Next Steps

To go further, choose one of the following:

- [Concepts](concepts/index.md) explains signals, spans, context storage and propagation.
- [Instrument an Order Service](tutorials/instrument-an-order-service.md) is a hands-on tutorial that builds a larger traced application.
- [OpenTelemetry](opentelemetry.md) is the entry point to the reference pages for every layer and service.
- [How to Export Signals with OTLP](how-to/export-signals-with-otlp.md) replaces the console exporter with a real backend.
