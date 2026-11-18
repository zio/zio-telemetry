---
id: instrument-an-order-service
title: "Tutorial: Instrument an Order Service"
sidebar_label: "Instrument an Order Service"
description: "Build a small order service step by step and instrument it with ZIO Telemetry spans, attributes, events, metrics, logs, and propagated context."
keywords:
  - "Observability"
  - "Distributed Tracing"
  - "OpenTelemetry"
  - "Tracing"
  - "Context Propagation"
---

Welcome to ZIO Telemetry! This tutorial is for Scala developers who know the basics of ZIO (effects, layers, and `for` comprehensions) and want to learn how to make a service observable. You don't need any prior experience with OpenTelemetry to follow along.

## Learning Objectives

By the end of this tutorial, you will understand:

- How to wire OpenTelemetry signals into a ZIO application with layers
- How to wrap effects in spans, and how to enrich them with attributes and events
- How to record a counter and a histogram
- How ZIO logs and log annotations become OpenTelemetry log records
- How a trace continues from one service to another through a carrier

We'll learn these concepts by building a small order service, one piece at a time:

1. Set Up the Layers
2. Trace Work with Spans
3. Add Attributes and Events
4. Count and Measure Orders
5. Log with Annotations
6. Continue a Trace Across Services
7. Putting It Together

We recommend reading from top to bottom, because each section builds on the previous one.

## Background

An observable service tells you what it is doing while it runs. OpenTelemetry describes this with three kinds of signals: traces (what happened during one request, and how long each part took), metrics (numbers aggregated over time, such as how many orders were placed), and logs (timestamped messages). ZIO Telemetry connects these signals to ZIO, so you write ordinary effects and the library takes care of keeping the current span with the current fiber.

The service we build is an order service. A gateway receives an order and calls the order endpoint, which validates the order, reserves stock, charges the payment, records metrics, and notifies the customer. To keep everything in one runnable program, the "network" between the gateway and the endpoint is a plain `Map[String, String]` of headers.

## 1. Set Up the Layers

Every ZIO Telemetry service needs an OpenTelemetry SDK instance and a place to keep the current context. First, we build the SDK layer. Here we export every signal to standard output in the OTLP JSON format, so you can run the program without any backend.

The following code creates a tracer provider, a meter provider, and a logger provider, and combines them into one `OpenTelemetry` instance:

```scala mdoc:silent
import io.opentelemetry.api
import io.opentelemetry.api.common.AttributeKey
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
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

def sdkLayer(serviceName: String): TaskLayer[api.OpenTelemetry] =
  OpenTelemetry.custom(
    for {
      resource       <- ZIO.succeed(
                          Resource.create(api.common.Attributes.of(AttributeKey.stringKey("service.name"), serviceName))
                        )
      spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
      tracerProvider <- ZIO.fromAutoCloseable(
                          ZIO.succeed(
                            SdkTracerProvider.builder().setResource(resource).addSpanProcessor(spanProcessor).build()
                          )
                        )
      metricExporter <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
      metricReader   <- ZIO.fromAutoCloseable(
                          ZIO.succeed(PeriodicMetricReader.builder(metricExporter).setInterval(5.seconds).build())
                        )
      meterProvider  <- ZIO.fromAutoCloseable(
                          ZIO.succeed(
                            SdkMeterProvider.builder().setResource(resource).registerMetricReader(metricReader).build()
                          )
                        )
      logExporter    <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingLogRecordExporter.create()))
      logProcessor   <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleLogRecordProcessor.create(logExporter)))
      loggerProvider <- ZIO.fromAutoCloseable(
                          ZIO.succeed(
                            SdkLoggerProvider.builder().setResource(resource).addLogRecordProcessor(logProcessor).build()
                          )
                        )
      sdk            <- ZIO.fromAutoCloseable(
                          ZIO.succeed(
                            OpenTelemetrySdk
                              .builder()
                              .setTracerProvider(tracerProvider)
                              .setMeterProvider(meterProvider)
                              .setLoggerProvider(loggerProvider)
                              .build()
                          )
                        )
    } yield sdk
  )
```

The code above:

- `resource` — describes the service that produces the signals, using the standard `service.name` attribute
- `spanExporter`, `metricExporter`, `logExporter` — print each signal to standard output instead of sending it to a backend
- `ZIO.fromAutoCloseable` — makes sure each provider is closed when the layer's scope ends, which also flushes pending data
- `OpenTelemetry.custom` — turns the scoped effect into a layer that provides the Java SDK's `api.OpenTelemetry` instance

Next, we turn the SDK into the three ZIO Telemetry services we use throughout the tutorial. We also add a context storage, which is the place where ZIO Telemetry keeps the current span for each fiber:

```scala mdoc:silent
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.tracing.Tracing

val telemetryLayer: TaskLayer[Tracing & Meter] =
  (sdkLayer("order-service") ++ OpenTelemetry.contextZIO) >>>
    (OpenTelemetry.tracing("order-service", logAnnotated = true) ++
      OpenTelemetry.metrics("order-service") ++
      OpenTelemetry.logging("order-service"))
```

The code above:

- `OpenTelemetry.contextZIO` — stores the current context in a `FiberRef`, which is the right choice when you don't use the Java agent
- `OpenTelemetry.tracing` — provides `Tracing`, with the instrumentation scope name `"order-service"`; `logAnnotated = true` copies ZIO log annotations onto new spans, as we use in section 5
- `OpenTelemetry.metrics` — provides `Meter` (and the builder behind it)
- `OpenTelemetry.logging` — routes `ZIO.log*` calls to OpenTelemetry log records

:::note
The layers do not print anything yet. Signals appear once we create spans, record measurements, and log messages in the next sections.
:::

## 2. Trace Work with Spans

A span represents one unit of work with a start time, an end time, and a name. Spans nest: a span created inside another span becomes its child, and together they form a trace. `Tracing#span` wraps an effect in a span, and ends the span when the effect finishes, whether it succeeds or fails.

We start with the checkout part of the service. The `Order` data type is shared by every piece we write, and `Checkout` places an order by validating it and then charging it. The sleeps stand in for real work:

```scala mdoc:silent
final case class Order(id: String, customer: String, items: Int, total: Double)

final class Checkout(tracing: Tracing) {

  def placeOrder(order: Order): UIO[Unit] =
    tracing.span("place-order") {
      validate(order) *> charge(order)
    }

  private def validate(order: Order): UIO[Unit] =
    tracing.span("validate-order")(ZIO.sleep(10.millis))

  private def charge(order: Order): UIO[Unit] =
    tracing.span("charge-payment")(ZIO.sleep(20.millis))

}
```

The code above:

- `place-order` — the outer span, which covers the whole method
- `validate-order` and `charge-payment` — child spans, because they run inside the outer span's effect
- `ZIO.sleep` — simulates work, so each span has a visible duration

When this runs, the trace looks like this tree, where each child is shorter than its parent:

```text
place-order
├── validate-order
└── charge-payment
```

## 3. Add Attributes and Events

A bare span tells you how long something took. Attributes and events tell you what was going on. An attribute is a key-value pair attached to the span, such as the order id. An event is a named, timestamped moment within the span, such as "stock reserved".

We add both in an `Inventory` service. We pass the starting attributes when the span is created, set another attribute while the span is running, and record two events, one of them with its own attributes:

```scala mdoc:silent
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}

final class Inventory(tracing: Tracing) {

  def reserve(order: Order): UIO[Unit] =
    tracing.span(
      "reserve-stock",
      attributes = Attributes(
        Attribute.string("order.id", order.id),
        Attribute.long("order.items", order.items.toLong)
      )
    ) {
      for {
        _ <- tracing.addEvent("stock-checked")
        _ <- tracing.setAttribute("warehouse", "eu-west-1")
        _ <- tracing.addEventWithAttributes(
               "stock-reserved",
               Attributes(Attribute.long("reserved", order.items.toLong))
             )
      } yield ()
    }

}
```

The code above:

- `Attributes(...)` with `Attribute.string` and `Attribute.long` — build typed attributes for the span at creation time
- `Tracing#setAttribute` — adds an attribute to the span that is current at that point
- `Tracing#addEvent` — records an event with just a name
- `Tracing#addEventWithAttributes` — records an event that carries its own attributes

:::tip
Set attributes and events on the current span from anywhere inside the span's effect. You don't need to pass the span around, because `Tracing` finds it through the fiber's context.
:::

## 4. Count and Measure Orders

Traces describe individual requests. Metrics describe the behaviour of the service over time. We use two instruments: a counter, which only goes up and answers "how many orders did we place?", and a histogram, which records a distribution and answers "how large are the orders?".

`Meter` creates instruments. We create them once, when the service starts, and keep them in an `OrderMetrics` class:

```scala mdoc:silent
import zio.telemetry.opentelemetry.metrics.{Counter, Histogram}

final class OrderMetrics(placed: Counter[Long], totals: Histogram[Double]) {

  def record(order: Order): UIO[Unit] = {
    val attributes = Attributes(Attribute.boolean("order.large", order.total >= 100.0))

    placed.inc(attributes) *> totals.record(order.total, attributes)
  }

}

object OrderMetrics {

  def make(meter: Meter): UIO[OrderMetrics] =
    for {
      placed <- meter.counter("orders_placed", Some("{order}"), Some("Number of orders placed"))
      totals <- meter.histogram(
                  "order_total",
                  Some("USD"),
                  Some("Value of placed orders"),
                  Some(Chunk(10.0, 50.0, 100.0, 500.0))
                )
    } yield new OrderMetrics(placed, totals)

}
```

The code above:

- `Meter#counter` — creates a `Counter[Long]` with a name, a unit, and a description
- `Meter#histogram` — creates a `Histogram[Double]`; the last argument sets the bucket boundaries
- `Counter#inc` — adds one to the counter, tagged with attributes
- `Histogram#record` — adds one observation to the distribution

Measurements are collected in memory and exported periodically. The reader we configured in section 1 exports every five seconds and once more when the layer closes, so a short program still prints its metrics.

## 5. Log with Annotations

With `OpenTelemetry.logging` in place, every `ZIO.log*` call becomes an OpenTelemetry log record. A log record emitted inside a span automatically carries that span's trace id and span id, so you can jump from a log line to the trace it belongs to. ZIO log annotations become attributes of the log record.

We add a `Notifier` that annotates its logs with the order id:

```scala mdoc:silent
final class Notifier {

  def confirm(order: Order): UIO[Unit] =
    ZIO.logAnnotate("order.id", order.id) {
      ZIO.logInfo("Order confirmed")
    }

}
```

The code above uses `ZIO.logAnnotate` to attach the order id to every log message inside its block, and `ZIO.logInfo` to emit the message at the `Info` level. `OpenTelemetry.logging` forwards messages at `Info` and above by default.

:::note
Because we passed `logAnnotated = true` to `OpenTelemetry.tracing` in section 1, annotations that are active when a span starts are also copied onto that span as attributes.
:::

## 6. Continue a Trace Across Services

So far everything ran inside one service. In a real system, a request crosses process boundaries, and the trace must continue on the other side. To do that, the caller writes the identity of its current span into a carrier, which is a mutable container that travels with the request (for example, HTTP headers). The callee reads the carrier and creates its span as a child of the caller's span.

The simulated upstream service is a `Gateway`. It starts a root span, injects the span's context into an `OutgoingContextCarrier`, and "sends" the resulting headers by calling a function, which stands in for the network:

```scala mdoc:silent
import io.opentelemetry.api.trace.SpanKind
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

final class Gateway(tracing: Tracing, send: (Map[String, String], Order) => UIO[Unit]) {

  def receive(order: Order): UIO[Unit] =
    tracing.root("gateway-request", SpanKind.CLIENT) {
      val carrier = OutgoingContextCarrier.default()

      for {
        _ <- tracing.injectSpan(TraceContextPropagator.default, carrier)
        _ <- send(carrier.kernel.toMap, order)
      } yield ()
    }

}
```

The code above:

- `Tracing#root` — starts a new trace with the span `gateway-request`, which does not have a parent
- `OutgoingContextCarrier.default()` — creates a carrier backed by a mutable `Map[String, String]`
- `Tracing#injectSpan` — writes the current span's context into the carrier using the W3C Trace Context format
- `carrier.kernel.toMap` — the plain map we use as our stand-in for HTTP headers
- `send` — a function that stands in for the HTTP call; in the final program it is the endpoint's `handle` method

The downstream side is the `OrderEndpoint`. It wraps the received headers in an `IncomingContextCarrier`, and `Tracing#extractSpan` creates a `SERVER` span that is a child of the gateway's span. Inside it we call all the pieces from the previous sections:

```scala mdoc:silent
final class OrderEndpoint(
  tracing: Tracing,
  checkout: Checkout,
  inventory: Inventory,
  metrics: OrderMetrics,
  notifier: Notifier
) {

  def handle(headers: Map[String, String], order: Order): UIO[Unit] = {
    val carrier = IncomingContextCarrier.default(mutable.Map.from(headers))

    tracing.extractSpan(TraceContextPropagator.default, carrier, "handle-order", SpanKind.SERVER) {
      inventory.reserve(order) *>
        checkout.placeOrder(order) *>
        metrics.record(order) *>
        notifier.confirm(order)
    }
  }

}
```

The code above uses `IncomingContextCarrier.default` to read the headers, and `Tracing#extractSpan` to continue the caller's trace. The whole trace now has one trace id, shared by the gateway and the endpoint:

```text
gateway-request
└── handle-order
    ├── reserve-stock
    ├── place-order
    │   ├── validate-order
    │   └── charge-payment
    └── (log record: "Order confirmed", with the same trace id)
```

:::caution
In this tutorial both "services" run in one process and share one SDK, which keeps the program short. In a real deployment each service has its own process and its own layers, and only the carrier crosses the network.
:::

## 7. Putting It Together

The program below combines everything we built: the SDK and telemetry layers from section 1, the `Checkout`, `Inventory`, `OrderMetrics`, and `Notifier` pieces from sections 2 to 5, and the `Gateway` and `OrderEndpoint` from section 6. It differs from the sections in that it is one self-contained file with a `ZIOAppDefault` entry point, which builds the services from the layer's `Tracing` and `Meter` and sends a single order through the gateway:

```scala mdoc:compile-only
import io.opentelemetry.api
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
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
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.metrics.{Counter, Histogram, Meter}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

final case class Order(id: String, customer: String, items: Int, total: Double)

final class Checkout(tracing: Tracing) {

  def placeOrder(order: Order): UIO[Unit] =
    tracing.span("place-order") {
      validate(order) *> charge(order)
    }

  private def validate(order: Order): UIO[Unit] =
    tracing.span("validate-order")(ZIO.sleep(10.millis))

  private def charge(order: Order): UIO[Unit] =
    tracing.span("charge-payment")(ZIO.sleep(20.millis))

}

final class Inventory(tracing: Tracing) {

  def reserve(order: Order): UIO[Unit] =
    tracing.span(
      "reserve-stock",
      attributes = Attributes(
        Attribute.string("order.id", order.id),
        Attribute.long("order.items", order.items.toLong)
      )
    ) {
      for {
        _ <- tracing.addEvent("stock-checked")
        _ <- tracing.setAttribute("warehouse", "eu-west-1")
        _ <- tracing.addEventWithAttributes(
               "stock-reserved",
               Attributes(Attribute.long("reserved", order.items.toLong))
             )
      } yield ()
    }

}

final class OrderMetrics(placed: Counter[Long], totals: Histogram[Double]) {

  def record(order: Order): UIO[Unit] = {
    val attributes = Attributes(Attribute.boolean("order.large", order.total >= 100.0))

    placed.inc(attributes) *> totals.record(order.total, attributes)
  }

}

object OrderMetrics {

  def make(meter: Meter): UIO[OrderMetrics] =
    for {
      placed <- meter.counter("orders_placed", Some("{order}"), Some("Number of orders placed"))
      totals <- meter.histogram(
                  "order_total",
                  Some("USD"),
                  Some("Value of placed orders"),
                  Some(Chunk(10.0, 50.0, 100.0, 500.0))
                )
    } yield new OrderMetrics(placed, totals)

}

final class Notifier {

  def confirm(order: Order): UIO[Unit] =
    ZIO.logAnnotate("order.id", order.id) {
      ZIO.logInfo("Order confirmed")
    }

}

final class OrderEndpoint(
  tracing: Tracing,
  checkout: Checkout,
  inventory: Inventory,
  metrics: OrderMetrics,
  notifier: Notifier
) {

  def handle(headers: Map[String, String], order: Order): UIO[Unit] = {
    val carrier = IncomingContextCarrier.default(mutable.Map.from(headers))

    tracing.extractSpan(TraceContextPropagator.default, carrier, "handle-order", SpanKind.SERVER) {
      inventory.reserve(order) *>
        checkout.placeOrder(order) *>
        metrics.record(order) *>
        notifier.confirm(order)
    }
  }

}

final class Gateway(tracing: Tracing, send: (Map[String, String], Order) => UIO[Unit]) {

  def receive(order: Order): UIO[Unit] =
    tracing.root("gateway-request", SpanKind.CLIENT) {
      val carrier = OutgoingContextCarrier.default()

      for {
        _ <- tracing.injectSpan(TraceContextPropagator.default, carrier)
        _ <- send(carrier.kernel.toMap, order)
      } yield ()
    }

}

object OrderServiceApp extends ZIOAppDefault {

  def sdkLayer(serviceName: String): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        resource       <- ZIO.succeed(
                            Resource.create(api.common.Attributes.of(AttributeKey.stringKey("service.name"), serviceName))
                          )
        spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
        spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
        tracerProvider <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              SdkTracerProvider.builder().setResource(resource).addSpanProcessor(spanProcessor).build()
                            )
                          )
        metricExporter <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
        metricReader   <- ZIO.fromAutoCloseable(
                            ZIO.succeed(PeriodicMetricReader.builder(metricExporter).setInterval(5.seconds).build())
                          )
        meterProvider  <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              SdkMeterProvider.builder().setResource(resource).registerMetricReader(metricReader).build()
                            )
                          )
        logExporter    <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingLogRecordExporter.create()))
        logProcessor   <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleLogRecordProcessor.create(logExporter)))
        loggerProvider <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              SdkLoggerProvider.builder().setResource(resource).addLogRecordProcessor(logProcessor).build()
                            )
                          )
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setMeterProvider(meterProvider)
                                .setLoggerProvider(loggerProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  val telemetryLayer: TaskLayer[Tracing & Meter] =
    (sdkLayer("order-service") ++ OpenTelemetry.contextZIO) >>>
      (OpenTelemetry.tracing("order-service", logAnnotated = true) ++
        OpenTelemetry.metrics("order-service") ++
        OpenTelemetry.logging("order-service"))

  override def run: Task[Unit] =
    (for {
      tracing  <- ZIO.service[Tracing]
      meter    <- ZIO.service[Meter]
      metrics  <- OrderMetrics.make(meter)
      endpoint  = new OrderEndpoint(tracing, new Checkout(tracing), new Inventory(tracing), metrics, new Notifier)
      gateway   = new Gateway(tracing, endpoint.handle)
      _        <- gateway.receive(Order("order-1", "alice", items = 3, total = 120.0))
    } yield ()).provide(telemetryLayer)

}
```

Run this program with `sbt run` in a project that has the dependencies from [Getting Started](../getting-started.md). The standard output shows the spans, one log record, and (after the periodic reader fires or the program exits) the `orders_placed` and `order_total` metrics, all in OTLP JSON.

## What You've Learned

In this tutorial, you learned:

- How to build an OpenTelemetry SDK layer and combine it with `OpenTelemetry.contextZIO`, `OpenTelemetry.tracing`, `OpenTelemetry.metrics`, and `OpenTelemetry.logging`
- How to wrap effects in nested spans with `Tracing#span`, and how to enrich them with attributes and events
- How to create a `Counter` and a `Histogram` from a `Meter` and record measurements with attributes
- How `ZIO.log*` calls and `ZIO.logAnnotate` become OpenTelemetry log records
- How `Tracing#injectSpan` and `Tracing#extractSpan` with carriers continue one trace across two services

You now have a solid foundation for instrumenting ZIO services. The next step is to send these signals to a real backend and to propagate context over a real protocol.

## Where to Go Next

- **Ready to send signals to a backend?** Follow the how-to guide [How to Export Signals with OTLP](../how-to/export-signals-with-otlp.md).
- **Want to propagate context over HTTP?** Read [How to Propagate Context over HTTP](../how-to/propagate-context-over-http.md).
- **Want to see logs and traces connected?** Read [How to Correlate Logs with Traces](../how-to/correlate-logs-with-traces.md).
- **Want the full API for each piece?** Read the reference pages for [Tracing](../reference/tracing.md), [Metrics](../reference/metrics.md), and [Logging](../reference/logging.md).
- **Want to see the real thing in a running project?** Explore the [OpenTelemetry Example](../opentelemetry-example.md).
