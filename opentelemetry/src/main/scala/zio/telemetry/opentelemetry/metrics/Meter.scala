package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

trait Meter {

  def counter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  ): UIO[Counter[Long]]

  def upDownCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  ): UIO[UpDownCounter[Long]]

  def histogram(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  ): UIO[Histogram[Double]]

  def observableCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit]

  def observableUpDownCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit]

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
