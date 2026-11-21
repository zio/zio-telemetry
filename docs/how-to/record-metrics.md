---
id: record-metrics
title: "How to Record Metrics"
sidebar_label: "Record Metrics"
description: "Create counters, up-down counters, histograms and observable gauges with the ZIO Telemetry Meter and record measurements with attributes."
keywords:
  - "metrics"
  - "Meter"
  - "Counter"
  - "Histogram"
  - "OpenTelemetry.metrics"
---

In this guide, we will add metrics to a small order-processing function: a counter for orders placed, an up-down counter for orders in progress, a histogram for processing time, and an observable gauge that reports a queue depth on demand. We create the instruments once, in a layer, and record measurements with attributes.

## The Problem

Logs and spans describe individual requests. To answer questions such as how many orders per minute fail or how slow the 95th percentile is, we need aggregated numbers. The OpenTelemetry Java API offers instruments for this, but they are mutable Java objects, they take a `Context` argument to link measurements to the current trace, and they know nothing about ZIO log annotations. Calling them from effects by hand means threading the context through yourself.

ZIO Telemetry wraps the instruments so that each recording is a `UIO` that reads the current context from the `ContextStorage` and, optionally, adds the current ZIO log annotations as attributes.

## Prerequisites

We need `zio-opentelemetry` and a configured SDK, as in [How to Export Signals with OTLP](export-signals-with-otlp.md):

```sbt
// ZIO Telemetry for OpenTelemetry
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

These are the imports for the guide:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.metrics.{Counter, Histogram, Meter, UpDownCounter}
```

## Creating the Instruments

`Meter` is the factory for instruments. Each factory method returns a `UIO`, so we create the instruments inside a layer and share them. The `name` is the only required parameter, and `unit` and `description` are optional. A counter only goes up, an up-down counter goes up and down, and a histogram records a distribution of values and can take explicit bucket `boundaries`:

```scala mdoc:silent
case class OrderMetrics(
  placed: Counter[Long],
  inProgress: UpDownCounter[Long],
  duration: Histogram[Double]
)

object OrderMetrics {

  val live: URLayer[Meter, OrderMetrics] =
    ZLayer {
      for {
        meter      <- ZIO.service[Meter]
        placed     <- meter.counter("orders.placed", unit = Some("{order}"), description = Some("Orders placed"))
        inProgress <- meter.upDownCounter("orders.in_progress", unit = Some("{order}"))
        duration   <- meter.histogram(
                        "orders.duration",
                        unit = Some("s"),
                        description = Some("Time spent processing an order"),
                        boundaries = Some(Chunk(0.1, 0.5, 1.0, 5.0))
                      )
      } yield OrderMetrics(placed, inProgress, duration)
    }

}
```

## Recording Measurements

Recording is an ordinary effect. `Counter#inc` adds one and `Counter#add` adds a non-negative amount. `UpDownCounter#inc` and `UpDownCounter#dec` move the value in either direction, and `Histogram#record` stores one observation. Each method takes an optional `Attributes` value, which splits the metric into series, for example by payment method.

We build the attributes with `Attribute.string` and the `Attributes.apply` overloads. The order function increments the in-progress counter, runs the work, and always decrements it again with `ZIO#ensuring`:

```scala mdoc:silent
def processOrder(metrics: OrderMetrics, payment: String): UIO[Unit] = {
  val attributes = Attributes(Attribute.string("payment", payment))

  val work = ZIO.sleep(50.millis)

  metrics.inProgress.inc(attributes) *>
    work.timed
      .flatMap { case (elapsed, _) =>
        metrics.duration.record(elapsed.toMillis / 1000.0, attributes) *> metrics.placed.inc(attributes)
      }
      .ensuring(metrics.inProgress.dec(attributes))
}
```

:::warning
Attribute values become separate time series. Avoid unbounded values such as order identifiers or user emails, or the number of series will grow without limit.
:::

## Reporting Values on Demand

Some values are better sampled than incremented, such as a queue depth or a cache size. `Meter#observableGauge`, `Meter#observableCounter` and `Meter#observableUpDownCounter` register a callback that the SDK invokes when it collects metrics. The callback receives an `ObservableMeasurement`, and we call `ObservableMeasurement#record` with the current value.

The registration needs a `Scope`, because closing the scope unregisters the instrument. This layer reports the size of a queue:

```scala mdoc:silent
def queueDepthGauge(queue: Queue[String]): RLayer[Meter, Unit] =
  ZLayer.scoped {
    ZIO.serviceWithZIO[Meter] { meter =>
      meter.observableGauge("orders.queue_depth", description = Some("Orders waiting")) { measurement =>
        queue.size.flatMap(size => measurement.record(size.toDouble))
      }
    }
  }
```

The callback runs on a thread owned by the SDK's metric reader, so keep it fast and free of blocking work.

## Adding ZIO Log Annotations as Attributes

If we create the metrics layer with `logAnnotated = true`, every recording also carries the current ZIO log annotations as attributes. That lets us tag metrics with request-scoped data set via `ZIO.logAnnotate` without passing it to each call:

```scala mdoc:silent
def metricsLayer =
  OpenTelemetry.metrics("com.example.orders", logAnnotated = true)
```

## Wiring and Running

`OpenTelemetry.metrics` needs the SDK layer and a `ContextStorage`, and it provides `Meter`. We use `OpenTelemetry.noop` here so the snippet runs anywhere; replace it with your SDK layer. Because measurements read the current context, one recorded inside a span is associated with that trace:

```scala mdoc:compile-only
val app: Task[Unit] =
  ZIO
    .serviceWithZIO[OrderMetrics](metrics => ZIO.logAnnotate("tenant", "acme")(processOrder(metrics, "card")))
    .provide(
      OpenTelemetry.noop,
      OpenTelemetry.contextZIO,
      metricsLayer,
      OrderMetrics.live
    )
```

## Exporting ZIO Runtime Metrics

ZIO tracks its own metrics, such as fiber counts and executor statistics. `OpenTelemetry.zioMetrics` registers a listener that forwards them to the same instrument builder as your own metrics. It takes the `Instrument.Builder` that `OpenTelemetry.metrics` provides. For JVM metrics, add `DefaultJvmMetrics.live.unit` to the layer graph as well:

```scala mdoc:compile-only
val runtimeMetrics: ZLayer[Any, Throwable, Unit] =
  ZLayer.make[Unit](
    OpenTelemetry.noop,
    OpenTelemetry.contextZIO,
    OpenTelemetry.metrics("com.example.orders"),
    OpenTelemetry.zioMetrics
  )
```

## Going Further

- The [Metrics reference](../reference/metrics.md) documents every instrument and the observable variants.
- [How to Test Traced Code](test-traced-code.md) shows how to read recorded data with the in-memory exporters of the SDK testing module.
- [How to Export Signals with OTLP](export-signals-with-otlp.md) sends the metrics to a collector.
