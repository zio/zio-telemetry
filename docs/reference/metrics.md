---
id: metrics
title: "Metrics"
---

The metrics API records measurements through instruments that are created by a `Meter`. The OpenTelemetry SDK aggregates the measurements and exports them as metrics. Every synchronous instrument writes with the OpenTelemetry context held by the context storage, so a measurement can be associated with the active span. This page lists the instruments, how they are created, and how ZIO's own metrics are forwarded to OpenTelemetry.

## Creating the Meter Layer

`OpenTelemetry.metrics` builds the `Meter` service from an OpenTelemetry instance and a context storage. It also exposes the lower-level `Instrument.Builder` service, which the ZIO metrics bridge needs:

```scala
object OpenTelemetry {
  def metrics(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  ): URLayer[api.OpenTelemetry & ContextStorage, Meter & Instrument.Builder]
}
```

When `logAnnotated` is `true`, the layer merges the ZIO log annotations of the calling fiber into the attributes of every measurement of the synchronous instruments. Attributes passed explicitly at the call site win over annotations with the same key. To build the layer with the context storage that suits your setup:

```scala mdoc:silent
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument

def metricsLayer: ULayer[Meter & Instrument.Builder] =
  (OpenTelemetry.noop ++ OpenTelemetry.contextZIO) >>> OpenTelemetry.metrics("com.example.shop")
```

Instrumentation scope, layers and context storages are covered in [OpenTelemetry Layers](opentelemetry-layers.md) and [Context Storage](context-storage.md).

## Meter

`Meter` is the factory of instruments. Every method takes the instrument `name`, and an optional `unit` and `description`. Names must start with a letter and contain at most 255 alphanumeric characters, `_`, `.` or `-`, and units must be at most 63 ASCII characters. The service has two kinds of factory methods: synchronous instruments, which you call when something happens, and observable instruments, which the SDK polls through a callback. Each kind comes in a `Long` and a `Double` variant, named with a `Long` or `Double` suffix. The trait declares these methods:

```scala
trait Meter {
  def counterLong(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[Counter[Long]]
  def counterDouble(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[Counter[Double]]
  def upDownCounterLong(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[UpDownCounter[Long]]
  def upDownCounterDouble(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[UpDownCounter[Double]]
  def histogramLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Long]] = None
  ): UIO[Histogram[Long]]
  def histogramDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Double]] = None
  ): UIO[Histogram[Double]]
  def gaugeLong(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[Gauge[Long]]
  def gaugeDouble(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[Gauge[Double]]
  def observableCounterLong(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Long] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableCounterDouble(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Double] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableUpDownCounterLong(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Long] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableUpDownCounterDouble(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Double] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableGaugeLong(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Long] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableGaugeDouble(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Double] => Task[Unit]
  ): RIO[Scope, Unit]
}
```

The unsuffixed methods `Meter#counter`, `Meter#upDownCounter`, `Meter#histogram`, `Meter#observableCounter`, `Meter#observableUpDownCounter` and `Meter#observableGauge` are deprecated since 3.1.23. Each one forwards to the variant for its original value type: `Long` for the counters, `Double` for the histogram and the gauge. Use the suffixed methods in new code.

## Synchronous Instruments

The instruments in this section record a value at the point where the event occurs. Each recording method has the shape `(value, attributes) => UIO[Unit]`, where `attributes` defaults to empty attributes. The value is associated with the context currently held by the context storage.

### Counter

`Meter#counterLong` returns a `Counter[Long]` and `Meter#counterDouble` returns a `Counter[Double]`. A counter is a monotonically increasing sum. The value passed to `Counter#add` must be non-negative, and `Counter#inc` adds one:

```scala
trait Counter[-A] {
  def add(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]
  def inc(attributes: Attributes = Attributes.empty): UIO[Unit]
}
```

To count processed orders per payment method:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.metrics.Meter

val program: URIO[Meter, Unit] =
  for {
    meter   <- ZIO.service[Meter]
    counter <- meter.counterLong("orders.processed", unit = Some("{order}"), description = Some("Processed orders"))
    _       <- counter.inc(Attributes(Attribute.string("payment.method", "card")))
    _       <- counter.add(5L)
  } yield ()
```

When the measured quantity is fractional, such as the amount of money processed, use `Meter#counterDouble` and pass `Double` values to `Counter#add`.

### UpDownCounter

`Meter#upDownCounterLong` returns an `UpDownCounter[Long]` and `Meter#upDownCounterDouble` returns an `UpDownCounter[Double]`. It is a sum that can also decrease, such as the number of items in a queue. `UpDownCounter#add` accepts positive, negative or zero values, and the trait adds `UpDownCounter#dec` next to `UpDownCounter#inc`:

```scala
trait UpDownCounter[-A] {
  def add(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]
  def inc(attributes: Attributes = Attributes.empty): UIO[Unit]
  def dec(attributes: Attributes = Attributes.empty): UIO[Unit]
}
```

To track the number of active sessions:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.metrics.Meter

val program: URIO[Meter, Unit] =
  for {
    meter    <- ZIO.service[Meter]
    sessions <- meter.upDownCounterLong("sessions.active")
    _        <- sessions.inc()
    _        <- sessions.dec()
  } yield ()
```

### Histogram

`Meter#histogramLong` returns a `Histogram[Long]` and `Meter#histogramDouble` returns a `Histogram[Double]`. A histogram records the distribution of values such as latencies. `Histogram#record` expects a non-negative value. The optional `boundaries` argument sets the explicit bucket boundaries as advice to the SDK, which a configured view can still override. The boundaries have the same type as the recorded values:

```scala
trait Histogram[-A] {
  def record(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]
}
```

To record request durations in seconds with custom buckets:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.metrics.Meter

val program: URIO[Meter, Unit] =
  for {
    meter    <- ZIO.service[Meter]
    duration <- meter.histogramDouble(
                  "http.server.duration",
                  unit = Some("s"),
                  description = Some("Request duration"),
                  boundaries = Some(Chunk(0.05, 0.1, 0.5, 1.0, 5.0))
                )
    _        <- duration.record(0.23)
  } yield ()
```

For `Meter#histogramDouble`, the `Double.MaxValue` boundary is dropped before it reaches OpenTelemetry, because OpenTelemetry represents the upper bound of the last bucket implicitly.

### Gauge

`Meter#gaugeLong` returns a `Gauge[Long]` and `Meter#gaugeDouble` returns a `Gauge[Double]`. A gauge records the current value of something that is not a sum, such as a temperature, at the moment your code knows it. `Gauge#set` replaces the previous value:

```scala
trait Gauge[-A] {
  def set(value: A, attributes: Attributes = Attributes.empty): UIO[Unit]
}
```

To record the temperature of a room whenever a sensor reports it:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.metrics.Meter

val program: URIO[Meter, Unit] =
  for {
    meter       <- ZIO.service[Meter]
    temperature <- meter.gaugeDouble("room.temperature", unit = Some("Cel"))
    _           <- temperature.set(21.5, Attributes(Attribute.string("room", "kitchen")))
  } yield ()
```

To report a value that the SDK should sample on its own schedule instead, use an observable gauge from the next section.

## Observable Instruments

Observable instruments report a value that the application does not push, such as a cache size or a temperature. The SDK invokes the callback on every collection cycle, and the callback reports values through `ObservableMeasurement`:

```scala
trait ObservableMeasurement[-A] {
  def record(value: A, attributes: Attributes = Attributes.empty): Task[Unit]
}
```

`Meter#observableCounterLong`, `Meter#observableUpDownCounterLong` and `Meter#observableGaugeLong` observe `Long` values, and the `Double` variants observe `Double` values. A counter callback reports the cumulative total rather than an increment. Each method returns `RIO[Scope, Unit]`: the instrument is closed when the scope ends, so the callback stops being invoked. The callback runs on a fresh runtime, so it cannot read the environment or fiber state of the code that registered it. To observe the size of a `Ref` as a gauge:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.metrics.Meter

val program: RIO[Meter & Scope, Unit] =
  for {
    meter <- ZIO.service[Meter]
    ref   <- Ref.make(0L)
    _     <- meter.observableGaugeLong("cache.size", unit = Some("{entry}")) { om =>
               ref.get.flatMap(size => om.record(size))
             }
  } yield ()
```

## ZIO Metrics Bridge

`OpenTelemetry.zioMetrics` forwards metrics defined with the ZIO `Metric` API to OpenTelemetry. It requires the `Instrument.Builder` produced by `OpenTelemetry.metrics`, and it is scoped: the bridge is installed when the layer is built and removed when it is released. The layer has this signature:

```scala
object OpenTelemetry {
  def zioMetrics: URLayer[Instrument.Builder, Unit]
}
```

The layer enables the ZIO runtime metrics and registers a ZIO `MetricListener` that translates updates into OpenTelemetry instruments, creating each one lazily on first use and keying it by the ZIO metric name and tags. The mapping is:

| ZIO Metric  | OpenTelemetry Instrument                                                           |
| ----------- | ---------------------------------------------------------------------------------- |
| `Counter`   | `Counter[Double]`, the value is recorded as a `Double`                             |
| `Gauge`     | Observable gauge that reports the last value set, or the value after increments    |
| `Histogram` | `Histogram[Double]` with the ZIO boundaries passed as bucket boundaries            |
| `Frequency` | `Counter[Long]` incremented by one, with an additional `bucket` attribute          |
| `Summary`   | Not forwarded, updates are ignored                                                 |

ZIO metric tags become attributes of the measurement and the ZIO metric description becomes the instrument description. JVM metrics are not forwarded by default. To include them, add `DefaultJvmMetrics.live.unit` to the layers. To install the bridge and define a ZIO metric that reaches OpenTelemetry:

```scala mdoc:compile-only
import zio._
import zio.metrics.Metric
import zio.telemetry.opentelemetry.OpenTelemetry

val program: ZIO[Any, Nothing, Unit] =
  Metric.counter("jobs.completed").increment.provide(
    (OpenTelemetry.noop ++ OpenTelemetry.contextZIO) >>>
      OpenTelemetry.metrics("com.example.jobs") >>>
      OpenTelemetry.zioMetrics
  )
```

The example is illustrative: the listener is removed as soon as the provided layer's scope closes, so in an application you provide the layer to the whole program.

## Related Pages

To combine metrics with the other signals in a running service, see [How to Record Metrics](../how-to/record-metrics.md). For the layers that build the OpenTelemetry instance, see [OpenTelemetry Layers](opentelemetry-layers.md).
