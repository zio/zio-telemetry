package zio.telemetry.opentelemetry.metrics.internal

import io.opentelemetry.api
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.{Counter, Histogram, ObservableMeasurement, UpDownCounter}

import scala.jdk.CollectionConverters._

trait Instrument[-A] {

  def record0(
    value: A,
    attributes: api.common.Attributes = api.common.Attributes.empty,
    context: Context = Context.root()
  ): Unit

}

object Instrument {

  trait Builder {

    def counter(name: String, unit: Option[String] = None, description: Option[String] = None): Counter[Long]

    def upDownCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): UpDownCounter[Long]

    def histogram(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None,
      boundaries: Option[Chunk[Double]] = None
    ): Histogram[Double]

    def observableCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongCounter

    def observableUpDownCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongUpDownCounter

    def observableGauge(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleGauge

  }

  private[opentelemetry] object Builder {

    def live(logAnnotated: Boolean = false): URLayer[api.metrics.Meter with ContextStorage, Builder] =
      ZLayer(
        for {
          meter      <- ZIO.service[api.metrics.Meter]
          ctxStorage <- ZIO.service[ContextStorage]
        } yield new Builder {

          override def counter(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          ): Counter[Long] = {
            val builder = meter.counterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Counter.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def upDownCounter(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          ): UpDownCounter[Long] = {
            val builder = meter.upDownCounterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            UpDownCounter.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def histogram(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None,
            boundaries: Option[Chunk[Double]] = None
          ): Histogram[Double] = {
            val builder = meter.histogramBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)
            boundaries.foreach(seq => builder.setExplicitBucketBoundariesAdvice(seq.map(Double.box).asJava))

            Histogram.double(builder.build(), ctxStorage, logAnnotated)
          }

          override def observableCounter(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongCounter = {
            val builder = meter.counterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { om =>
              callback(ObservableMeasurement.long(om))
            }
          }

          override def observableUpDownCounter(
            name: String,
            unit: Option[String],
            description: Option[String]
          )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongUpDownCounter = {
            val builder = meter.upDownCounterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { om =>
              callback(ObservableMeasurement.long(om))
            }
          }

          override def observableGauge(
            name: String,
            unit: Option[String],
            description: Option[String]
          )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleGauge = {
            val builder = meter.gaugeBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { om =>
              callback(ObservableMeasurement.double(om))
            }
          }

        }
      )

  }

}
