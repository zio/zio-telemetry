package zio.telemetry.opentelemetry.metrics.internal

import io.opentelemetry.api
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.{Counter, Gauge, Histogram, ObservableMeasurement, UpDownCounter}

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

    def counterLong(name: String, unit: Option[String] = None, description: Option[String] = None): Counter[Long]

    def counterDouble(name: String, unit: Option[String] = None, description: Option[String] = None): Counter[Double]

    def upDownCounterLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): UpDownCounter[Long]

    def upDownCounterDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): UpDownCounter[Double]

    def upDownCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): UpDownCounter[Long] =
      upDownCounterLong(name, unit, description)

    def histogramLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None,
      boundaries: Option[Chunk[Long]] = None
    ): Histogram[Long]

    def histogramDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None,
      boundaries: Option[Chunk[Double]] = None
    ): Histogram[Double]

    def histogram(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None,
      boundaries: Option[Chunk[Double]] = None
    ): Histogram[Double] =
      histogramDouble(name, unit, description, boundaries)

    def gaugeLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): Gauge[Long]

    def gaugeDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    ): Gauge[Double]

    def observableCounterLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongCounter

    def observableCounterDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleCounter

    def observableCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongCounter =
      observableCounterLong(name, unit, description)(callback)

    def observableUpDownCounterLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongUpDownCounter

    def observableUpDownCounterDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleUpDownCounter

    def observableUpDownCounter(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongUpDownCounter =
      observableUpDownCounterLong(name, unit, description)(callback)

    def observableGaugeLong(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongGauge

    def observableGaugeDouble(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleGauge

    def observableGauge(
      name: String,
      unit: Option[String] = None,
      description: Option[String] = None
    )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleGauge =
      observableGaugeDouble(name, unit, description)(callback)

  }

  private[opentelemetry] object Builder {

    def live(logAnnotated: Boolean = false): URLayer[api.metrics.Meter & ContextStorage, Builder] =
      ZLayer(
        for {
          meter      <- ZIO.service[api.metrics.Meter]
          ctxStorage <- ZIO.service[ContextStorage]
        } yield new Builder {

          override def counterLong(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          ): Counter[Long] = {
            val builder = meter.counterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Counter.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def counterDouble(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          ): Counter[Double] = {
            val builder = meter.counterBuilder(name).ofDoubles()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Counter.double(builder.build(), ctxStorage, logAnnotated)
          }

          override def upDownCounterLong(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None
          ): UpDownCounter[Long] = {
            val builder = meter.upDownCounterBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            UpDownCounter.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def histogramDouble(
            name: String,
            unit: Option[String] = None,
            description: Option[String] = None,
            boundaries: Option[Chunk[Double]] = None
          ): Histogram[Double] = {
            val builder = meter.histogramBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)
            boundaries.foreach { seq =>
              builder.setExplicitBucketBoundariesAdvice(
                seq
                  // ZIO uses explicit Double.MaxValue boundary as upper bound. OTEL uses internal "inf" value instead
                  .filter(_ != Double.MaxValue)
                  .map(Double.box)
                  .asJava
              )
            }

            Histogram.double(builder.build(), ctxStorage, logAnnotated)
          }

          override def observableCounterLong(
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

          override def observableUpDownCounterLong(
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

          override def observableGaugeDouble(
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

          override def upDownCounterDouble(
            name: String,
            unit: Option[String],
            description: Option[String]
          ): UpDownCounter[Double] = {
            val builder = meter.upDownCounterBuilder(name).ofDoubles()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            UpDownCounter.double(builder.build(), ctxStorage, logAnnotated)
          }

          override def histogramLong(
            name: String,
            unit: Option[String],
            description: Option[String],
            boundaries: Option[Chunk[Long]]
          ): Histogram[Long] = {
            val builder = meter.histogramBuilder(name).ofLongs()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            boundaries.foreach(seq => builder.setExplicitBucketBoundariesAdvice(seq.map(Long.box).asJava))

            Histogram.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def gaugeDouble(
            name: String,
            unit: Option[String],
            description: Option[String]
          ): Gauge[Double] = {
            val builder = meter.gaugeBuilder(name)

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Gauge.double(builder.build(), ctxStorage, logAnnotated)
          }

          override def gaugeLong(
            name: String,
            unit: Option[String],
            description: Option[String]
          ): Gauge[Long] = {
            val builder = meter.gaugeBuilder(name).ofLongs()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            Gauge.long(builder.build(), ctxStorage, logAnnotated)
          }

          override def observableCounterDouble(
            name: String,
            unit: Option[String],
            description: Option[String]
          )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleCounter = {
            val builder = meter.counterBuilder(name).ofDoubles()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { measurement =>
              callback(ObservableMeasurement.double(measurement))
            }
          }

          override def observableUpDownCounterDouble(
            name: String,
            unit: Option[String],
            description: Option[String]
          )(callback: ObservableMeasurement[Double] => Unit): api.metrics.ObservableDoubleUpDownCounter = {
            val builder = meter.upDownCounterBuilder(name).ofDoubles()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { measurement =>
              callback(ObservableMeasurement.double(measurement))
            }
          }

          override def observableGaugeLong(
            name: String,
            unit: Option[String],
            description: Option[String]
          )(callback: ObservableMeasurement[Long] => Unit): api.metrics.ObservableLongGauge = {
            val builder = meter.gaugeBuilder(name).ofLongs()

            unit.foreach(builder.setUnit)
            description.foreach(builder.setDescription)

            builder.buildWithCallback { measurement =>
              callback(ObservableMeasurement.long(measurement))
            }
          }

        }
      )

  }

}
