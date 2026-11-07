---
id: record-metrics-with-opentelemetry
title: "Record Metrics with OpenTelemetry"
description: "Create counters, histograms, up-down counters, gauges, and observable instruments in a ZIO application and export them with OpenTelemetry."
---

In this guide, we will record business metrics from a small checkout service using `zio-opentelemetry`. By the end, you will have a `Meter` that creates counters, histograms, up-down counters, and gauges, and reports values reported by a callback. We take an incremental approach: first we set up a `Meter`, then we record one instrument type at a time, and finally we attach exporters so the values leave the process.

## The Problem

A service that wants to publish metrics through the OpenTelemetry Java API must create instruments from a `io.opentelemetry.api.metrics.Meter`, keep references to them, and pass each measurement along with its attributes. Nothing in that API ties a measurement to the ZIO effect that produced it, so every recording site becomes a side-effecting call that is easy to forget or to duplicate.

For example, the following code records an order count directly through the Java API:

```scala mdoc:compile-only
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.GlobalOpenTelemetry

val javaMeter = GlobalOpenTelemetry.getMeter("checkout")

val ordersPlaced = javaMeter
  .counterBuilder("orders.placed")
  .setDescription("Number of orders placed")
  .build()

def placeOrderDirectly(region: String): Unit = {
  // ... business logic ...
  ordersPlaced.add(1L, Attributes.of(AttributeKey.stringKey("region"), region))
}
```

This works, but `placeOrder` is an ordinary function, so nothing forces the caller to run the recording inside the same fiber or scope as the business logic. The rest of this guide shows how to express the same recordings as ZIO effects and how to provide the `Meter` through a layer.

## Prerequisites

Add `zio-opentelemetry` to your `build.sbt`:

```scala
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

The library provides the OpenTelemetry API, but it does not provide an SDK. To export metrics, you also need the SDK and an exporter, which we add in the [Exporting Metrics](#exporting-metrics) section.

The examples in this guide use these imports:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.core.metrics.{Counter, Gauge, Histogram, Meter, UpDownCounter}
```

`OpenTelemetry` is the layer constructor object. `core.OpenTelemetry` is the service that the layers provide, and `Meter` is the service that the instruments come from.

## Providing a Meter

A `Meter` is a service built from an `OpenTelemetry` service. The `OpenTelemetry` service is itself a layer, so the wiring has two steps. First, provide the `core.OpenTelemetry` service. Then, build a `Meter` from it with `OpenTelemetry.meter`, passing the instrumentation scope name that identifies this library in your telemetry backend:

```scala mdoc:compile-only
val checkoutScope = "com.example.checkout"

val openTelemetryLayer: TaskLayer[core.OpenTelemetry] =
  OpenTelemetry.custom(ZIO.succeed(io.opentelemetry.api.OpenTelemetry.noop()))

val meterLayer: RLayer[core.OpenTelemetry, Meter] =
  OpenTelemetry.meter(checkoutScope)

val appLayer: TaskLayer[Meter] =
  openTelemetryLayer >>> meterLayer
```

`OpenTelemetry.custom` takes a scoped effect that returns a configured Java `OpenTelemetry` instance, so the SDK is closed when the layer's scope ends. The example above uses the no-op implementation from the OpenTelemetry API, which records nothing. The [Exporting Metrics](#exporting-metrics) section replaces it with an SDK that exports data.

:::tip
If the application already registers a global OpenTelemetry instance through the Java agent, use `OpenTelemetry.global()` instead of `OpenTelemetry.custom()`. Both produce the same `core.OpenTelemetry` service.
:::

## Recording Counts

A counter only goes up. Use one to count events such as orders placed. `Meter#counter` returns a `Counter[Long]` directly, without an effect, so you create the instrument once and share it:

```scala mdoc:silent
def placeOrder(orders: Counter[Long], region: String): UIO[Unit] =
  for {
    _ <- ZIO.logInfo(s"placing order in $region")
    _ <- orders.inc(Attributes(Attribute.string("region", region)))
  } yield ()
```

`Counter#inc` adds one to the counter, and `Counter#add` adds an arbitrary non-negative amount. Both return a `UIO[Unit]`, so they compose with the rest of your effect and do not need a separate `Scope`.

To create the counter inside a layer and keep it in the environment:

```scala mdoc:compile-only
val ordersLayer: URLayer[Meter, Counter[Long]] =
  ZLayer(ZIO.serviceWith[Meter](_.counter("orders.placed", description = Some("Number of orders placed"))))
```

## Recording Distributions with Histograms

A histogram records a distribution of values, such as request latency. `Meter#histogram` takes an optional list of bucket boundaries. Without them, the SDK uses its default boundaries.

To record how long checkout takes in milliseconds:

```scala mdoc:silent
def checkout(duration: Histogram[Double]): UIO[Unit] =
  for {
    start   <- Clock.nanoTime
    _       <- ZIO.sleep(20.millis)
    end     <- Clock.nanoTime
    elapsed  = (end - start).toDouble / 1000000.0
    _       <- duration.record(elapsed, Attributes(Attribute.string("outcome", "ok")))
  } yield ()
```

`Histogram#record` takes the value and the attributes, and returns a `UIO[Unit]`.

## Tracking Values That Go Up and Down

An up-down counter tracks a value that can increase and decrease, such as the number of items in carts. `UpDownCounter#inc` and `UpDownCounter#dec` change it by one, and `UpDownCounter#add` changes it by any signed amount:

```scala mdoc:silent
def addToCart(items: UpDownCounter[Long]): UIO[Unit] =
  items.inc()

def removeFromCart(items: UpDownCounter[Long]): UIO[Unit] =
  items.dec()
```

Use an up-down counter when the value is a sum of changes. Use a gauge when the value is a reading that replaces the previous one.

## Recording Current Values with Gauges

A gauge records the latest value of something that has a current state, such as the stock level of an item. `Gauge#set` replaces the previous reading:

```scala mdoc:silent
def updateStock(stock: Gauge[Double], remaining: Int): UIO[Unit] =
  stock.set(remaining.toDouble, Attributes(Attribute.string("sku", "book-123")))
```

## Reporting Values with Observable Instruments

Observable instruments do not record values when your code calls them. Instead, the SDK calls a callback at each collection interval, and the callback reports the current value. This suits values that you can read on demand, such as the size of a queue.

`Meter#observableGauge` takes the callback as a second parameter list. It returns a `RIO[Scope, Unit]`, because the registration lasts as long as the scope:

```scala mdoc:compile-only
def trackQueueDepth(meter: Meter, queue: Queue[String]): RIO[Scope, Unit] =
  meter.observableGauge("checkout.queue.depth", description = Some("Orders waiting to be processed")) { om =>
    queue.size.flatMap(size => om.record(size.toDouble))
  }
```

Run the registration inside a scope that lives as long as you want the callback active, for example by providing it to the `run` method of a `ZIOAppDefault` through `ZIO.scoped`.

## Adding Attributes

Every recording method takes an `Attributes` argument that becomes a label on the data point. Build the set with `Attributes.apply`, one `Attribute` per key:

```scala mdoc:silent
def orderAttributes(region: String, items: Long, express: Boolean): io.opentelemetry.api.common.Attributes =
  Attributes(
    Attribute.string("region", region),
    Attribute.long("items", items),
    Attribute.boolean("express", express)
  )
```

Calling `orderAttributes("eu-west", 3L, express = true)` in the recording code produces one series for each distinct combination of the three values.

Attributes that differ create separate series. Keep the number of distinct values low: a value such as an order identifier would create a new series for each order.

## Exporting Metrics

The `OpenTelemetry.custom` layer accepts whatever SDK configuration you provide. To send metrics out of the process, add a `SdkMeterProvider` with a periodic reader and an exporter. The exporter below writes OTLP JSON to standard output, which needs the `opentelemetry-exporter-logging-otlp` artifact:

```scala
libraryDependencies += "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % "<opentelemetry-java-version>"
```

With the dependency in place, the setup creates an exporter, a periodic reader that calls it every five seconds, and an SDK that uses the reader:

```scala
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingMetricExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader

val exportingLayer: TaskLayer[core.OpenTelemetry] =
  OpenTelemetry.custom(
    for {
      exporter      <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
      reader        <- ZIO.fromAutoCloseable(
                         ZIO.succeed(PeriodicMetricReader.builder(exporter).setInterval(java.time.Duration.ofSeconds(5)).build())
                       )
      meterProvider <- ZIO.fromAutoCloseable(
                         ZIO.succeed(SdkMeterProvider.builder().registerMetricReader(reader).build())
                       )
      sdk           <- ZIO.fromAutoCloseable(
                         ZIO.succeed(OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build())
                       )
    } yield sdk
  )
```

`PeriodicMetricReader` collects and exports metrics every five seconds. Replace `OtlpJsonLoggingMetricExporter` with an OTLP gRPC or HTTP exporter to send metrics to a collector.

The repository contains a complete, runnable version of this setup in the `modules/examples/opentelemetry/manual` project, along with the instrumented HTTP services that record these metrics. See the [OpenTelemetry Example](opentelemetry-example.md) for the walkthrough.

## Going Further

- The [OpenTelemetry](opentelemetry.md) page describes the full set of layers, including `OpenTelemetry.enableZioMetrics`, which exposes ZIO runtime metrics through the same meter.
- The `Meter` trait lists every instrument constructor, and each instrument documents its own methods in the Scaladoc of `zio.telemetry.opentelemetry.core.metrics`.
