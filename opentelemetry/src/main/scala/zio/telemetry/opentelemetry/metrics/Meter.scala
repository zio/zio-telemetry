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
  def counter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(implicit trace: Trace): UIO[Counter[Long]]

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
  )(implicit trace: Trace): UIO[UpDownCounter[Long]]

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
  )(implicit trace: Trace): UIO[Histogram[Double]]

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
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

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
  )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

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
  )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit]

}

object Meter {

  def live: URLayer[Instrument.Builder, Meter] =
    ZLayer(
      for {
        builder <- ZIO.service[Instrument.Builder]
      } yield new Meter {

        private val unsafeRuntime =
          Runtime.default.unsafe

        override def counter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(implicit trace: Trace): UIO[Counter[Long]] =
          ZIO.succeed(builder.counter(name, unit, description))

        override def upDownCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(implicit trace: Trace): UIO[UpDownCounter[Long]] =
          ZIO.succeed(builder.upDownCounter(name, unit, description))

        override def histogram(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None,
          boundaries: Option[Chunk[Double]] = None
        )(implicit trace: Trace): UIO[Histogram[Double]] =
          ZIO.succeed(builder.histogram(name, unit, description, boundaries))

        override def observableCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                builder.observableCounter(name, unit, description) { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(om)).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

        override def observableUpDownCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Long] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                builder.observableUpDownCounter(name, unit, description) { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(om)).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

        override def observableGauge(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Double] => Task[Unit])(implicit trace: Trace): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                builder.observableGauge(name, unit, description) { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(om)).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

      }
    )

}
