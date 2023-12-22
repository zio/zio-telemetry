package zio.telemetry.opentelemetry.metrics

import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import io.opentelemetry.api

trait Meter {

  def counter(
    name: String,
    unit: Option[String] = None,
    description: Option[String] = None
  ): Task[Counter[Long]]

}

object Meter {

  def live: URLayer[api.metrics.Meter with ContextStorage, Meter] =
    ZLayer(
      for {
        meter      <- ZIO.service[api.metrics.Meter]
        ctxStorage <- ZIO.service[ContextStorage]
      } yield new Meter {

        override def counter(
          name: String,
          unit: Option[String] = None,
          description: Option[String] = None
        ): Task[Counter[Long]] = ZIO.attempt {
          val builder = meter.counterBuilder(name)

          unit.foreach(builder.setUnit)
          description.foreach(builder.setDescription)

          Counter.long(builder.build(), ctxStorage)
        }

      }
    )

}
