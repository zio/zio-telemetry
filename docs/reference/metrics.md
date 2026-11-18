---
id: metrics
title: "Metrics"
---

The metrics API records measurements through instruments that are created by a `Meter`. The OpenTelemetry SDK aggregates the measurements and exports them as metrics. Every instrument writes with the OpenTelemetry context of the current fiber, so a measurement can be associated with the active span. This page lists the instruments, how they are created, and how ZIO's own metrics are forwarded to OpenTelemetry.

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

val metricsLayer: ULayer[Meter & Instrument.Builder] =
  (OpenTelemetry.noop ++ OpenTelemetry.contextZIO) >>> OpenTelemetry.metrics("com.example.shop")
```

Instrumentation scope, layers and context storages are covered in [OpenTelemetry Layers](opentelemetry-layers.md) and [Context Storage](context-storage.md).

## Meter

`Meter` is the factory of instruments. Every method takes the instrument `name`, and an optional `unit` and `description`. Names must start with a letter and contain at most 255 alphanumeric characters, `_`, `.` or `-`, and units must be at most 63 ASCII characters. The service has two kinds of factory methods: synchronous instruments, which you call when something happens, and observable instruments, which the SDK polls through a callback.

```scala
trait Meter {
  def counter(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[Counter[Long]]
  def upDownCounter(name: String, unit: Option[String] = None, description: Option[String] = None): UIO[UpDownCounter[Long]]
  def histogram(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Double]] = None
  ): UIO[Histogram[Double]]
  def observableCounter(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Long] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableUpDownCounter(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Long] => Task[Unit]
  ): RIO[Scope, Unit]
  def observableGauge(name: String, unit: Option[String] = None, description: Option[String] = None)(
    callback: ObservableMeasurement[Double] => Task[Unit]
  ): RIO[Scope, Unit]
}
```

## Synchronous Instruments

The instruments in this section record a value at the point where the event occurs. Each recording method has the shape `(value, attributes) => UIO[Unit]`, where `attributes` defaults to empty attributes. The value is associated with the context currently held by the context storage.

### Counter

`Meter#counter` returns a `Counter[Long]`, a monotonically increasing sum. The value passed to `Counter#add` must be non-negative, and `Counter#inc` adds one:

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
    counter <- meter.counter("orders.processed", unit = Some("{order}"), description = Some("Processed orders"))
    _       <- counter.inc(Attributes(Attribute.string("payment.method", "card")))
    _       <- counter.add(5L)
  } yield ()
```

### UpDownCounter

`Meter#upDownCounter` returns an `UpDownCounter[Long]`, a sum that can also decrease, such as the number of items in a queue. `UpDownCounter#add` accepts positive, negative or zero values, and the trait adds `UpDownCounter#dec` next to `UpDownCounter#inc`:

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
    sessions <- meter.upDownCounter("sessions.active")
    _        <- sessions.inc()
    _        <- sessions.dec()
  } yield ()
```

### Histogram

`Meter#histogram` returns a `Histogram[Double]` that records the distribution of values such as latencies. `Histogram#record` expects a non-negative value. The optional `boundaries` argument sets the explicit bucket boundaries as advice to the SDK, which a configured view can still override:

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
    duration <- meter.histogram(
                  "http.server.duration",
                  unit = Some("s"),
                  description = Some("Request duration"),
                  boundaries = Some(Chunk(0.05, 0.1, 0.5, 1.0, 5.0))
                )
    _        <- duration.record(0.23)
  } yield ()
```

The `Double.MaxValue` boundary is dropped before it reaches OpenTelemetry, because OpenTelemetry represents the upper bound of the last bucket implicitly.

## Observable Instruments

Observable instruments report a value that the application does not push, such as a cache size or a temperature. The SDK invokes the callback on every collection cycle, and the callback reports values through `ObservableMeasurement`:

```scala
trait ObservableMeasurement[-A] {
  def record(value: A, attributes: Attributes = Attributes.empty): Task[Unit]
}
```

`Meter#observableCounter` and `Meter#observableUpDownCounter` observe `Long` values, and `Meter#observableGauge` observes `Double` values. A counter callback reports the cumulative total rather than an increment. Each method returns `RIO[Scope, Unit]`: the instrument is closed when the scope ends, so the callback stops being invoked. The callback runs on a fresh runtime, so it cannot read the environment or fiber state of the code that registered it. To observe the size of a `Ref` as a gauge:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.metrics.Meter

val program: RIO[Meter & Scope, Unit] =
  for {
    meter <- ZIO.service[Meter]
    ref   <- Ref.make(0L)
    _     <- meter.observableGauge("cache.size", unit = Some("{entry}")) { om =>
               ref.get.flatMap(size => om.record(size.toDouble))
             }
  } yield ()
```

## ZIO Metrics Bridge

`OpenTelemetry.zioMetrics` forwards metrics defined with the ZIO `Metric` API to OpenTelemetry. It requires the `Instrument.Builder` produced by `OpenTelemetry.metrics`, and it is scoped: the bridge is installed when the layer is built and removed when it is released.

```scala
object OpenTelemetry {
  def zioMetrics: URLayer[Instrument.Builder, Unit]
}
```

The layer enables the ZIO runtime metrics and registers a ZIO `MetricListener` that translates updates into OpenTelemetry instruments, creating each one lazily on first use and keying it by the ZIO metric name and tags. The mapping is:

| ZIO Metric  | OpenTelemetry Instrument                                                           |
| ----------- | ---------------------------------------------------------------------------------- |
| `Counter`   | `Counter[Long]`, the value is truncated to a `Long`                                |
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
