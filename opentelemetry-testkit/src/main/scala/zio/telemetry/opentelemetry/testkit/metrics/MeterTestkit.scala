package zio.telemetry.opentelemetry.testkit.metrics

import io.opentelemetry.api.metrics.{Meter => JMeter}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument

import scala.jdk.CollectionConverters._

trait MeterTestkit {

  def collectCounterMetrics(implicit trace: Trace): UIO[List[MetricData.Counter]]

  def collectGaugeMetrics(implicit trace: Trace): UIO[List[MetricData.Gauge]]

  def collectHistogramMetrics(implicit trace: Trace): UIO[List[MetricData.Histogram]]

  def getMeter(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  )(implicit trace: Trace): Task[Meter]

  trait UnsafeAPI {

    def getMeter(
      instrumentationScopeName: String,
      instrumentationVersion: Option[String] = None,
      schemaUrl: Option[String] = None
    )(implicit trace: Trace): Task[JMeter]

    def getMeterProvider: SdkMeterProvider

  }

  def unsafe: UnsafeAPI

}

object MeterTestkit {

  def inMemory(implicit trace: Trace): RLayer[ContextStorage, MeterTestkit] =
    ZLayer {
      for {
        metricReader  <- ZIO.attempt(InMemoryMetricReader.create())
        meterProvider <- ZIO.attempt(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        ctxStorage    <- ZIO.service[ContextStorage]
      } yield new MeterTestkit {

        override def collectCounterMetrics(implicit trace: Trace): UIO[List[MetricData.Counter]] =
          ZIO.succeed(
            metricReader
              .collectAllMetrics()
              .asScala
              .toList
              .filter(_.getType == MetricDataType.LONG_SUM)
              .map(MetricData.Counter(_))
          )

        override def collectGaugeMetrics(implicit trace: Trace): UIO[List[MetricData.Gauge]] =
          ZIO.succeed(
            metricReader
              .collectAllMetrics()
              .asScala
              .toList
              .filter(_.getType == MetricDataType.DOUBLE_GAUGE)
              .map(MetricData.Gauge(_))
          )

        override def collectHistogramMetrics(implicit trace: Trace): UIO[List[MetricData.Histogram]] =
          ZIO.succeed(
            metricReader
              .collectAllMetrics()
              .asScala
              .toList
              .filter(_.getType == MetricDataType.HISTOGRAM)
              .map(MetricData.Histogram(_))
          )

        override def getMeter(
          instrumentationScopeName: String,
          instrumentationVersion: Option[String],
          schemaUrl: Option[String],
          logAnnotated: Boolean
        )(implicit trace: Trace): Task[Meter] =
          for {
            jmeter <- unsafe.getMeter(instrumentationScopeName, instrumentationVersion, schemaUrl)
            builder = Instrument.Builder.make(jmeter, ctxStorage, logAnnotated)
            meter   = Meter.make(builder)
          } yield meter

        override def unsafe: UnsafeAPI =
          new UnsafeAPI {

            override def getMeter(
              instrumentationScopeName: String,
              instrumentationVersion: Option[String] = None,
              schemaUrl: Option[String] = None
            )(implicit trace: Trace): Task[JMeter] = ZIO.attempt {
              val builder = meterProvider.meterBuilder(instrumentationScopeName)

              instrumentationVersion.foreach(builder.setInstrumentationVersion)
              schemaUrl.foreach(builder.setSchemaUrl)

              builder.build
            }

            override def getMeterProvider: SdkMeterProvider =
              meterProvider

          }

      }
    }

}
