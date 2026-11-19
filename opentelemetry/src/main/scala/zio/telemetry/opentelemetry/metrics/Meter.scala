package zio.telemetry.opentelemetry.metrics

import zio._
import zio.telemetry.opentelemetry.metrics.internal.Instrument

/**
 * Provides instruments used to record measurements which are aggregated to metrics.
 *
 * @see
 *   <a
 *   href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/supplementary-guidelines.md#instrument-selection">Instrument
 *   Selection Guidelines </a>
 */
trait Meter {

  /**
   * Constructs a Counter instrument.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   */
  def counterLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Counter[Long]]

  def counterDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Counter[Double]]

  /** Records Int values using an OpenTelemetry Long instrument. */
  def counterInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Counter[Int]]

  @deprecated("Use counterLong instead", "3.1.23")
  def counter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Counter[Long]] =
    counterLong(name, unit, description)

  /** Constructs a synchronous UpDownCounter for Long values. */
  def upDownCounterLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[UpDownCounter[Long]]

  /** Constructs a synchronous UpDownCounter for Double values. */
  def upDownCounterDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[UpDownCounter[Double]]

  /** Records Int values using an OpenTelemetry Long instrument. */
  def upDownCounterInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[UpDownCounter[Int]]

  /**
   * Constructs a UpDownCounter instrument.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   */
  def upDownCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[UpDownCounter[Long]] =
    upDownCounterLong(name, unit, description)

  /** Constructs a synchronous Histogram for Long values. */
  def histogramLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Long]] = None
  )(implicit trace: Trace): UIO[Histogram[Long]]

  /** Constructs a synchronous Histogram for Double values. */
  def histogramDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Double]] = None
  )(implicit trace: Trace): UIO[Histogram[Double]]

  /** Records Int values using an OpenTelemetry Long instrument. */
  def histogramInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Int]] = None
  )(implicit trace: Trace): UIO[Histogram[Int]]

  /**
   * Constructs a Historgram instrument.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   * @param boundaries
   *   the explicit bucket boundaries advice
   */
  def histogram(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None,
    boundaries: Option[Chunk[Double]] = None
  )(implicit trace: Trace): UIO[Histogram[Double]] =
    histogramDouble(name, unit, description, boundaries)

  /** Constructs a synchronous Gauge for Long values. */
  def gaugeLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Gauge[Long]]

  /** Constructs a synchronous Gauge for Double values. */
  def gaugeDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Gauge[Double]]

  /** Records Int values using an OpenTelemetry Long instrument. */
  def gaugeInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Gauge[Int]]

  /** Observes Long values with an asynchronous Counter. The callback is registered for the lifetime of the scope. */
  def observableCounterLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /** Observes Double values with an asynchronous Counter. The callback is registered for the lifetime of the scope. */
  def observableCounterDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Observes Int values using an OpenTelemetry Long instrument. The callback is registered for the lifetime of the
   * scope.
   */
  def observableCounterInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Builds an Asynchronous Counter instrument with the given callback.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   * @param callback
   *   callback which observes measurements when invoked
   */
  def observableCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
    observableCounterLong(name, unit, description)(callback)

  /**
   * Observes Long values with an asynchronous UpDownCounter. The callback is registered for the lifetime of the scope.
   */
  def observableUpDownCounterLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Observes Double values with an asynchronous UpDownCounter. The callback is registered for the lifetime of the
   * scope.
   */
  def observableUpDownCounterDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Observes Int values using an OpenTelemetry Long instrument. The callback is registered for the lifetime of the
   * scope.
   */
  def observableUpDownCounterInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Builds an Asynchronous UpDownCounter instrument with the given callback.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   * @param callback
   *   callback which observes measurements when invoked
   */
  def observableUpDownCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
    observableUpDownCounterLong(name, unit, description)(callback)

  /** Observes Long values with an asynchronous Gauge. The callback is registered for the lifetime of the scope. */
  def observableGaugeLong(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /** Observes Double values with an asynchronous Gauge. The callback is registered for the lifetime of the scope. */
  def observableGaugeDouble(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Observes Int values using an OpenTelemetry Long instrument. The callback is registered for the lifetime of the
   * scope.
   */
  def observableGaugeInt(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

  /**
   * Builds an Asynchronous Gauge instrument with the given callback.
   *
   * @param name
   *   the name of the Counter. Instrument names must consist of 255 or fewer characters including alphanumeric, _, .,
   *   -, and start with a letter
   * @param unit
   *   instrument units must be 63 or fewer ASCII characters
   * @param description
   *   description is an optional free-form text provided by the author of the instrument. The API MUST treat it as an
   *   opaque string
   * @param callback
   *   callback which observes measurements when invoked
   */
  def observableGauge(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
    observableGaugeDouble(name, unit, description)(callback)

}

object Meter {

  def live: URLayer[Instrument.Builder, Meter] =
    ZLayer(
      for {
        builder <- ZIO.service[Instrument.Builder]
      } yield new Meter {

        private val unsafeRuntime = Runtime.default.unsafe

        override def counterLong(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(implicit trace: Trace): UIO[Counter[Long]] =
          ZIO.succeed(builder.counterLong(name, unit, description))

        override def counterDouble(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(implicit trace: Trace): UIO[Counter[Double]] =
          ZIO.succeed(builder.counterDouble(name, unit, description))

        override def counterInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[Counter[Int]] =
          ZIO.succeed(builder.counterInt(name, unit, description))

        override def upDownCounterLong(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[UpDownCounter[Long]] =
          ZIO.succeed(builder.upDownCounterLong(name, unit, description))

        override def upDownCounterDouble(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[UpDownCounter[Double]] =
          ZIO.succeed(builder.upDownCounterDouble(name, unit, description))

        override def upDownCounterInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[UpDownCounter[Int]] =
          ZIO.succeed(builder.upDownCounterInt(name, unit, description))

        override def histogramDouble(
          name: String,
          unit: Option[String],
          description: Option[String],
          boundaries: Option[Chunk[Double]]
        )(implicit trace: Trace): UIO[Histogram[Double]] =
          ZIO.succeed(builder.histogramDouble(name, unit, description, boundaries))

        override def histogramLong(
          name: String,
          unit: Option[String],
          description: Option[String],
          boundaries: Option[Chunk[Long]]
        )(implicit trace: Trace): UIO[Histogram[Long]] =
          ZIO.succeed(builder.histogramLong(name, unit, description, boundaries))

        override def histogramInt(
          name: String,
          unit: Option[String],
          description: Option[String],
          boundaries: Option[Chunk[Int]]
        )(implicit trace: Trace): UIO[Histogram[Int]] =
          ZIO.succeed(builder.histogramInt(name, unit, description, boundaries))

        override def gaugeDouble(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[Gauge[Double]] =
          ZIO.succeed(builder.gaugeDouble(name, unit, description))

        override def gaugeLong(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[Gauge[Long]] =
          ZIO.succeed(builder.gaugeLong(name, unit, description))

        override def gaugeInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(implicit trace: Trace): UIO[Gauge[Int]] =
          ZIO.succeed(builder.gaugeInt(name, unit, description))

        override def observableCounterLong(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Long](builder.observableCounterLong(name, unit, description)(_))(callback)

        override def observableCounterDouble(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Double](builder.observableCounterDouble(name, unit, description)(_))(callback)

        override def observableCounterInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Int](builder.observableCounterInt(name, unit, description)(_))(callback)

        override def observableUpDownCounterLong(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Long](builder.observableUpDownCounterLong(name, unit, description)(_))(callback)

        override def observableUpDownCounterDouble(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Double](builder.observableUpDownCounterDouble(name, unit, description)(_))(callback)

        override def observableUpDownCounterInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Int](builder.observableUpDownCounterInt(name, unit, description)(_))(callback)

        override def observableGaugeLong(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Long](builder.observableGaugeLong(name, unit, description)(_))(callback)

        override def observableGaugeDouble(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Double](builder.observableGaugeDouble(name, unit, description)(_))(callback)

        override def observableGaugeInt(
          name: String,
          unit: Option[String],
          description: Option[String]
        )(callback: ObservableMeasurement[Int] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          register[Int](builder.observableGaugeInt(name, unit, description)(_))(callback)

        private def register[A](build: (ObservableMeasurement[A] => Unit) => AutoCloseable)(
          callback: ObservableMeasurement[A] => Task[Unit]
        )(implicit trace: Trace): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                build { measurement =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(measurement)).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

      }
    )

}
