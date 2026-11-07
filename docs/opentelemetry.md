---
id: opentelemetry
title: "OpenTelemetry"
---

OpenTelemetry is a collection of tools, APIs, and SDKs. You can use it to instrument, generate, collect, and export telemetry data for analysis in order to understand your software's performance and behavior. Well known implementations are [Jaeger](https://www.jaegertracing.io) and [Zipkin](https://www.zipkin.io).

The library provides an idiomatic ZIO 2.0 interface to OpenTelemetry, ensuring seamless interoperability with the native ZIO capabilities and beyond.

Some of the key features:

- **ZIO native** - Pleasant API that leverages native ZIO features, such as [Resource Management](https://zio.dev/reference/resource/), [Depenency Injection](https://zio.dev/reference/di/), [Streaming](https://zio.dev/reference/stream/), [Logging](https://zio.dev/reference/observability/logging), [Metrics](https://zio.dev/reference/observability/metrics/), and [ZIO Aspect](https://zio.dev/reference/core/zio/#zio-aspect)
- **OpenTelemetry Java SDK and ZIO Runtime interoperability** - Protecting users from directly engaging in OTEL context manipulations, offering a straightforward and clear interface for instrumenting spans, metrics, logs, and baggage. In this scenario, the ZIO effect serves as the span's scope.
- **Seamless signals correlation** -  Automatically correlates spans, metrics, and logs with a surrounding span.
- **Integration with ZIO capabilities** - Propagation of log annotations, metrics, and other data from the ZIO runtime as OTEL attributes and metric signals.

## Installation

Add the following dependency to your `build.sbt` to use OpenTelemetry inside your ZIO application:

```scala
"dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

You will also need SDK dependencies to be able to provide configured instances of [Tracer](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/latest/io/opentelemetry/api/trace/Tracer.html), [Meter](https://javadoc.io/static/io.opentelemetry/opentelemetry-api/1.33.0/io/opentelemetry/api/metrics/Meter.html), and [Logger](https://javadoc.io/doc/io.opentelemetry/opentelemetry-api/latest/io/opentelemetry/api/logs/Logger.html), such as:

```scala
"io.opentelemetry"         % "opentelemetry-sdk"           % <opentelemetry-java-version>
"io.opentelemetry"         % "opentelemetry-exporter-otlp" % <opentelemetry-java-version>
"io.opentelemetry.semconv" % "opentelemetry-semconv"       % <opentelemetry-java-version>
```

For the complete list of available Java artifacts, please consult the information available at the [link](https://github.com/open-telemetry/opentelemetry-java#releases)

## Usage

The snippets on this page are compiled with the library, so they show the current 4.0 API. The complete, runnable applications are in the [manual example module](https://github.com/zio/zio-telemetry/tree/v4.0.0-rc/modules/examples/opentelemetry/manual) of the repository.

Upgrading from ZIO Telemetry 3.x? See [Migrate to ZIO Telemetry 4.0](migrate-to-zio-telemetry-4.md) for the breaking changes and their replacements.

### Setup

The `zio.telemetry.opentelemetry.OpenTelemetry` object (the entry point) offers layers that build a `zio.telemetry.opentelemetry.core.OpenTelemetry` service. Every other layer, such as the tracer, meter, or logger, takes that service as its input.

First, you need an instance of `io.opentelemetry.api.OpenTelemetry`. If you don't need automatic instrumentation, use the `OpenTelemetry.custom` layer. It receives a scoped ZIO effect, so the provided instance is closed when the application shuts down. The example below builds an SDK that prints tracing, metric, and log data to stdout in OTLP JSON format. The `OtelSdk` object is reused by the examples further down this page:

```scala mdoc:silent
import zio._
import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.logging.otlp.{
  OtlpJsonLoggingLogRecordExporter,
  OtlpJsonLoggingMetricExporter,
  OtlpJsonLoggingSpanExporter
}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.context.ContextPropagator

object OtelSdk {

  val resourceName = "my-service"

  val resource: Resource =
    Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, resourceName))

  val tracerProvider: RIO[Scope, SdkTracerProvider] =
    for {
      exporter  <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      processor <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(exporter)))
      provider  <- ZIO.fromAutoCloseable(
                     ZIO.succeed(
                       SdkTracerProvider
                         .builder()
                         .setResource(resource)
                         .addSpanProcessor(processor)
                         .build()
                     )
                   )
    } yield provider

  val meterProvider: RIO[Scope, SdkMeterProvider] =
    for {
      exporter <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
      reader   <- ZIO.fromAutoCloseable(ZIO.succeed(PeriodicMetricReader.builder(exporter).build()))
      provider <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      SdkMeterProvider
                        .builder()
                        .setResource(resource)
                        .registerMetricReader(reader)
                        .build()
                    )
                  )
    } yield provider

  val loggerProvider: RIO[Scope, SdkLoggerProvider] =
    for {
      exporter  <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingLogRecordExporter.create()))
      processor <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleLogRecordProcessor.create(exporter)))
      provider  <- ZIO.fromAutoCloseable(
                     ZIO.succeed(
                       SdkLoggerProvider
                         .builder()
                         .setResource(resource)
                         .addLogRecordProcessor(processor)
                         .build()
                     )
                   )
    } yield provider

  def custom(
    ctxPropagator: ContextPropagator = ContextPropagator.default,
    logAnnotated: Boolean = false
  ): TaskLayer[core.OpenTelemetry] =
    OpenTelemetry.custom(ctxPropagator, logAnnotated)(
      for {
        tracer <- tracerProvider
        meter  <- meterProvider
        logger <- loggerProvider
        sdk    <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OpenTelemetrySdk
                        .builder()
                        .setTracerProvider(tracer)
                        .setMeterProvider(meter)
                        .setLoggerProvider(logger)
                        .build()
                    )
                  )
      } yield sdk: api.OpenTelemetry
    )

}
```

`OpenTelemetry.custom` returns a `core.OpenTelemetry`. Each layer that needs it is then wired with `.provide`, for example `OtelSdk.custom` together with `OpenTelemetry.tracer(...)`.

The library depends only on `opentelemetry-api`. This means you manage the providers and dependencies of `opentelemetry-sdk` and `opentelemetry-exporter-*` in your application, as shown above.

#### Choosing a context propagator and log annotations

`OpenTelemetry.custom` takes two optional parameters before the scoped effect:

- `ctxPropagator` - the propagator used by `propagate` and `continue` (see [Context Propagation](#context-propagation)). It defaults to `ContextPropagator.default`, which combines W3C trace context and W3C baggage.
- `logAnnotated` - when `true`, ZIO log annotations are added as attributes to spans, metric data points, and baggage. It defaults to `false`.

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.context.ContextPropagator

val configured: TaskLayer[core.OpenTelemetry] =
  OtelSdk.custom(ContextPropagator.w3cTraceContext, logAnnotated = true)
```

#### Usage with OpenTelemetry automatic instrumentation

OpenTelemetry provides a [JVM agent for automatic instrumentation](https://opentelemetry.io/docs/instrumentation/java/automatic/) which supports many [popular Java libraries](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md).
Since [version 1.25.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.25.0) the OpenTelemetry JVM agent supports ZIO.

To make `zio-opentelemetry` interoperate with automatic instrumentation, the context must live in OpenTelemetry's native thread-local `Context`, and the tracer must come from the globally registered `TracerProvider`. Use the `OpenTelemetry.global` layer for this instead of `OpenTelemetry.custom`:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core

val autoInstrumented: TaskLayer[core.OpenTelemetry] =
  OpenTelemetry.global()
```

Wrap the code that should run in the instrumented context with `openTelemetry.autoinstrumented`, as described in the [Tracing](#tracing) section.

### Tracing

To send [Trace signals](https://opentelemetry.io/docs/concepts/signals/traces/), you need a `Tracer` service in your environment. Use the `OpenTelemetry.tracer` layer, which takes the `core.OpenTelemetry` service as its input.

The `Tracer` API includes methods for creating spans:

- `root` - starts a new root span, which is not a child of the current span. Its default span kind is `SpanKind.SERVER`.
- `span` - starts a child span of the current span.
- `spanScoped` - starts a child span that ends only when the surrounding `Scope` closes.
- `continueSpan` - starts a child of an existing `Span` that you pass in.
- `spanUnmanaged` - starts a span that you end yourself with `Span#end`.

The body of `root`, `span`, and `continueSpan` is a function from the `Span` to a ZIO effect, so the body can set attributes and events on its own span. The `Span` API includes:

- `setAttribute` - sets an attribute of the span
- `addEvent` - adds an event to the span
- `setStatus` - sets the status of the span
- `recordException` - records an exception on the span
- `end` - ends the span

Some of the methods above are also available via [ZIO Aspect](https://zio.dev/reference/core/zio/#zio-aspect) syntax, through `tracer.aspects`.

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core.trace.Tracer

object TracingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.TracingApp"

  override def run =
    ZIO
      .serviceWithZIO[Tracer] { tracer =>
        // Create a root span with a lifetime equal to the runtime of the given ZIO effect.
        tracer.root("root_span", SpanKind.INTERNAL) { span =>
          for {
            // Set an attribute to the current span
            _       <- span.setAttribute("attr1", "value1")
            // Add an event to the current span
            _       <- span.addEvent("Waiting for the user input")
            // Read user input
            message <- Console.readLine
            // Add another event to the current span
            _       <- span.addEvent(s"User typed: $message")
          } yield message
        }
      }
      .provide(
        OtelSdk.custom(),
        OpenTelemetry.tracer(instrumentationScopeName)
      )

}
```

The same root span can be created with the aspect syntax. Here the body does not need the span, so it is passed as a plain effect:

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.core.trace.Tracer

def handle(tracer: Tracer): UIO[Unit] =
  ZIO.logInfo("handling request") @@ tracer.aspects.root("root_span", SpanKind.INTERNAL)
```

### Metrics

To send [Metric signals](https://opentelemetry.io/docs/concepts/signals/metrics/), you need a `Meter` service in your environment. Use the `OpenTelemetry.meter` layer, which takes the `core.OpenTelemetry` service as its input.

The `Meter` API lets you create [Counter](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md#counter), [UpDownCounter](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md#updowncounter), [Gauge](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md#gauge), [Histogram](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md#histogram) and their [asynchronous](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md#asynchronous-instrument-api) (aka observable) counterparts.
Unlike in 3.x, the `Meter` methods return the instrument directly, not an effect that produces it.
As a rule of thumb, observable instruments must be initialized on an application startup. They are scoped, so you do not need to shut them down manually.
By default the metric instruments do not take ZIO log annotations into account. To turn it on, pass `logAnnotated = true` to the `OpenTelemetry.meter` layer initializer.

For a step-by-step walkthrough of each instrument, see [Record Metrics with OpenTelemetry](record-metrics-with-opentelemetry.md).

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.core.metrics.{Counter, Meter, ObservableMeasurement}
import zio.telemetry.opentelemetry.core.trace.Tracer

object MetricsApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.MetricsApp"

  // Stores the number of seconds elapsed since the application startup
  val tickRefLayer: ULayer[Ref[Long]] =
    ZLayer(
      for {
        ref <- Ref.make(0L)
        _   <- ref
                 .update(_ + 1)
                 .repeat[Any, Long](Schedule.spaced(1.second))
                 .forkDaemon
      } yield ref
    )

  // Records the number of seconds elapsed since the application startup
  val tickCounterLayer: RLayer[Meter & Ref[Long], Unit] =
    ZLayer.scoped(
      for {
        meter <- ZIO.service[Meter]
        ref   <- ZIO.service[Ref[Long]]
        // Initialize observable counter instrument
        _     <- meter.observableCounter("tick_counter") { (om: ObservableMeasurement[Long]) =>
                   for {
                     tick <- ref.get
                     _    <- om.record(tick)
                   } yield ()
                 }
      } yield ()
    )

  override def run =
    ZIO
      .serviceWithZIO[Tracer] { tracer =>
        ZIO.serviceWithZIO[Meter] { meter =>
          // The counter is created directly, not inside an effect
          val messageLengthCounter: Counter[Long] = meter.counter("message_length_counter")
          val logic = for {
            // Read user input
            message <- Console.readLine
            // Sleep for the number of seconds equal to the message length to demonstrate the work of the observable counter
            _       <- ZIO.sleep(message.length.seconds)
            // Record the message length
            _       <- messageLengthCounter.add(message.length.toLong, Attributes(Attribute.string("message", message)))
          } yield message

          // By wrapping our logic into a span, the `messageLengthCounter` data points are correlated with a "root_span" automatically.
          // Additionally, the `ZIO.logAnnotate` call adds one more attribute to the `messageLengthCounter`.
          ZIO.logAnnotate("zio", "annotation")(logic) @@ tracer.aspects.root("root_span", SpanKind.INTERNAL)
        }
      }
      .provide(
        OtelSdk.custom(),
        OpenTelemetry.meter(instrumentationScopeName, logAnnotated = true),
        OpenTelemetry.tracer(instrumentationScopeName),
        tickCounterLayer,
        tickRefLayer
      )

}
```

#### Integration with ZIO metrics

To enable seamless integration with [ZIO metrics](https://zio.dev/reference/observability/metrics/), use the `OpenTelemetry.enableZioMetrics` layer. It takes an instrumentation scope name, an optional version, and an optional schema URL, and it produces `Unit`. If you also need to publish JVM metrics, be sure to include `DefaultJvmMetrics.live.unit`.

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core

val zioMetrics: URLayer[core.OpenTelemetry, Unit] =
  OpenTelemetry.enableZioMetrics("dev.zio.MetricsApp")
```

### Logging

To send [Log signals](https://opentelemetry.io/docs/concepts/signals/logs/), install the OpenTelemetry logger with the `OpenTelemetry.installLogger` layer. It routes calls to `ZIO.log*` as OpenTelemetry log records, and it takes the same instrumentation scope name as the other layers, plus an optional log level (`LogLevel.Info` by default).

Use this layer when you need to propagate ZIO log annotations as log record attributes, or when you do not want to use auto-instrumentation. You can achieve the same by incorporating [Logger MDC auto-instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/logger-mdc-instrumentation.md), so the rule of thumb is to use `installLogger` only in those cases.

If you need a `ZLogger` value rather than a layer, for example to run an effect with `ZIO.withLogger`, use `OpenTelemetry.zioLogger`.

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core.trace.Tracer

object LoggingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.LoggingApp"

  override def run =
    ZIO
      .serviceWithZIO[Tracer] { tracer =>
        val logic = for {
          // Read user input
          message <- Console.readLine
          // Propagate a ZIO.logInfo message as an OTEL log signal and log annotations as log record attributes
          _       <- ZIO.logAnnotate("correlated", "true")(
                       ZIO.logInfo(s"User message: $message")
                     )
        } yield ()

        // All log messages produced by `logic` will be correlated with a "root_span" automatically
        logic @@ tracer.aspects.root("root_span", SpanKind.INTERNAL)
      }
      .provide(
        OtelSdk.custom(),
        OpenTelemetry.installLogger(instrumentationScopeName),
        OpenTelemetry.tracer(instrumentationScopeName)
      )

}
```

### Baggage

To pass contextual information in [Baggage](https://opentelemetry.io/docs/concepts/signals/baggage/), read the `baggage` member of the `core.OpenTelemetry` service. It is no longer a separate layer. The `Baggage` API includes methods for getting and setting key/value pairs, and for injecting and extracting baggage data through the propagators described in [Context Propagation](#context-propagation).

Setting a value takes the effect that the value applies to: `baggage.set(name, value)(zio)` makes the value visible inside `zio`, and it is not visible after that effect ends. Use `baggage.remove(name)(zio)` and `baggage.setWithMetadata(...)` the same way.

By default the `Baggage` service does not take ZIO log annotations into account. To turn it on, pass `logAnnotated = true` to the layer that builds `core.OpenTelemetry`, such as `OpenTelemetry.custom(logAnnotated = true)(...)`.

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.core

object BaggageApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.BaggageApp"

  override def run =
    ZIO
      .serviceWithZIO[core.OpenTelemetry] { openTelemetry =>
        for {
          // Read user input
          message <- Console.readLine
          // Set the baggage key for the duration of the inner effect, and read all baggage data
          // including ZIO log annotations
          data    <- openTelemetry.baggage.set("message", message) {
                       ZIO.logAnnotate("message2", "annotation")(
                         openTelemetry.baggage.getAll
                       )
                     }
          // Print the resulting data
          _       <- Console.printLine(s"Baggage data: $data")
        } yield ()
      }
      .provide(
        OtelSdk.custom(logAnnotated = true)
      )

}
```

### Context Propagation

Explicitly utilizing the context propagation API becomes relevant only when auto-instrumentation is not used. Use the `propagate` method to write the current context into an outgoing carrier, and the `continue` method to restore the context from an incoming carrier.
Please note that injection and extraction are not referentially transparent due to the use of the mutable OpenTelemetry carrier Java API.

The `ContextPropagator` used for this is set when the `core.OpenTelemetry` service is built (see [Choosing a context propagator](#choosing-a-context-propagator-and-log-annotations)). The default propagator combines the W3C trace context and W3C baggage formats.

```scala mdoc:compile-only
import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.core.trace.Tracer
import scala.collection.mutable

object PropagatingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.PropagatingApp"

  override def run =
    ZIO
      .serviceWithZIO[core.OpenTelemetry] { openTelemetry =>
        ZIO.serviceWithZIO[Tracer] { tracer =>
          // Representing the transport between the upstream and downstream services
          val kernel          = mutable.Map.empty[String, String]
          val outgoingCarrier = OutgoingContextCarrier.default(kernel)
          val incomingCarrier = IncomingContextCarrier.default(kernel)

          // Representing upstream service
          val upstreamService = for {
            // Read user input
            message <- Console.readLine
            // Set the baggage data for the duration of the upstream logic
            _       <- openTelemetry.baggage.set("message", message) {
                         tracer.root("upstream_root_span", SpanKind.INTERNAL) { span =>
                           for {
                             _ <- ZIO.logInfo(s"Message length is ${message.length}")
                             _ <- span.setAttribute("message", message)
                             // Write the current context, including baggage, into the outgoing carrier
                             _ <- openTelemetry.propagate(outgoingCarrier)
                           } yield ()
                         }
                       }
          } yield ()

          // Representing downstream service
          val downstreamService =
            // Restore the context from the incoming carrier
            openTelemetry.continue(incomingCarrier) {
              for {
                data    <- openTelemetry.baggage.getAll
                message  = data.getOrElse("message", "")
                _       <- ZIO.logInfo(s"Message length is ${message.length}")
                // Run the logic as a child span of the upstream root span
                _       <- tracer.span("downstream_span") { span =>
                             span.setAttribute("message", message)
                           }
              } yield ()
            }

          // Simulate the interaction between services
          upstreamService *> downstreamService
        }
      }
      .provide(
        OtelSdk.custom(),
        OpenTelemetry.tracer(instrumentationScopeName)
      )

}
```
