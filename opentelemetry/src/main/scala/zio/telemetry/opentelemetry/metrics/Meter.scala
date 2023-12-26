package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

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
  ): UIO[Counter[Long]]

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
  ): UIO[UpDownCounter[Long]]

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
   */
  def histogram(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  ): UIO[Histogram[Double]]

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
  )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit]

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
  )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit]

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
  )(callback: ObservableMeasurement[Double] => Task[Unit]): RIO[Scope, Unit]

}

object Meter {

  def live: URLayer[api.metrics.Meter with ContextStorage, Meter] =
    ZLayer(
      for {
        meter      <- ZIO.service[api.metrics.Meter]
        ctxStorage <- ZIO.service[ContextStorage]
      } yield new Meter {

        private val unsafeRuntime =
          Runtime.default.unsafe

        override def counter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        ): UIO[Counter[Long]] =
          ZIO.succeed {
            val builder = meter.counterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Counter.long(builder.build(), ctxStorage)
          }

        override def upDownCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        ): UIO[UpDownCounter[Long]] =
          ZIO.succeed {
            val builder = meter.upDownCounterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            UpDownCounter.long(builder.build(), ctxStorage)
          }

        override def histogram(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        ): UIO[Histogram[Double]] =
          ZIO.succeed {
            val builder = meter.histogramBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Histogram.double(builder.build(), ctxStorage)
          }

        override def observableCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                val builder = meter.counterBuilder(name)

                unit.foreach(builder.setUnit)
                description.foreach(builder.setDescription)

                builder.buildWithCallback { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(ObservableMeasurement.long(om))).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

        override def observableUpDownCounter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                val builder = meter.upDownCounterBuilder(name)

                unit.foreach(builder.setUnit)
                description.foreach(builder.setDescription)

                builder.buildWithCallback { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(ObservableMeasurement.long(om))).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

        override def observableGauge(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        )(callback: ObservableMeasurement[Double] => Task[Unit]): RIO[Scope, Unit] =
          ZIO
            .fromAutoCloseable(
              ZIO.attempt {
                val builder = meter.gaugeBuilder(name)

                unit.foreach(builder.setUnit)
                description.foreach(builder.setDescription)

                builder.buildWithCallback { om =>
                  Unsafe.unsafe { implicit unsafe =>
                    unsafeRuntime.run(callback(ObservableMeasurement.double(om))).getOrThrowFiberFailure()
                  }
                }
              }
            )
            .unit

      }
    )

}
