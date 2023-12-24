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

  def observableCounter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  )(callback: ObservableMeasurement[Long] => Task[Unit]): RIO[Scope, Unit]

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

      }
    )

}
