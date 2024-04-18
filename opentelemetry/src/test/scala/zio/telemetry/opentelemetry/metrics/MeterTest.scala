package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.{Tracing, TracingTest}
import zio.test.{TestEnvironment, ZIOSpecDefault, _}

import scala.jdk.CollectionConverters._

object MeterTest extends ZIOSpecDefault {

  val inMemoryMetricReaderLayer: ZLayer[Any, Nothing, InMemoryMetricReader] =
    ZLayer(ZIO.succeed(InMemoryMetricReader.create()))

  val meterLayer = {
    val jmeter = ZLayer {
      for {
        metricReader  <- ZIO.service[InMemoryMetricReader]
        meterProvider <- ZIO.succeed(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        meter         <- ZIO.succeed(meterProvider.get("MeterTest"))
      } yield meter
    }

    jmeter >>> Meter.live
  }

  val observableRefLayer: ULayer[Ref[Long]] =
    ZLayer(
      for {
        ref <- Ref.make(0L)
        _   <- ref
                 .update(_ + 1)
                 .repeat[Any, Long](Schedule.spaced(1.second))
                 .forkDaemon
      } yield ref
    )

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Meter")(
        normalSpec,
        contextualSpec
      )
    )

  private val normalSpec =
    suite("normal")(
      test("counter") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counter("test_counter")
            attributes       = Attributes(Attribute.long("attr1", 3L))
            _               <- counter.add(12, attributes)
            _               <- counter.inc(attributes)
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getLongSumData().getPoints().asScala.toList.head
            metricValue      = metricPoint.getValue()
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 13L,
            metricAttributes == attributes
          )
        }
      },
      test("upDownCounter") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.upDownCounter("test_up_down_counter")
            attributes       = Attributes(Attribute.boolean("attr2", value = false))
            _               <- counter.add(5, attributes)
            _               <- counter.inc(attributes)
            _               <- counter.dec(attributes)
            _               <- counter.dec(attributes)
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getLongSumData().getPoints().asScala.toList.head
            metricValue      = metricPoint.getValue()
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 4L,
            metricAttributes == attributes
          )
        }
      },
      test("histogram") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            histogram       <- meter.histogram("test_histogram")
            attributes       = Attributes(Attribute.double("attr3", 12.3))
            _               <- histogram.record(2.1, attributes)
            _               <- histogram.record(3.3, attributes)
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getHistogramData().getPoints().asScala.toList.head
            metricSum        = metricPoint.getSum()
            metricMin        = metricPoint.getMin()
            metricMax        = metricPoint.getMax()
            metricCount      = metricPoint.getCount()
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricSum == 5.4,
            metricMin == 2.1,
            metricMax == 3.3,
            metricCount == 2,
            metricAttributes == attributes
          )
        }
      },
      test("observableCounter") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader     <- ZIO.service[InMemoryMetricReader]
              ref        <- ZIO.service[Ref[Long]]
              _          <- meter.observableCounter("obs") { om =>
                              for {
                                v <- ref.get
                                _ <- om.record(v)
                              } yield ()
                            }
              _          <- TestClock.adjust(13.seconds)
              metric      = reader.collectAllMetrics().asScala.toList.head
              metricPoint = metric.getLongSumData().getPoints().asScala.toList.head
              metricValue = metricPoint.getValue()
            } yield assertTrue(metricValue == 14L)
          }
        )
      }
    ).provide(inMemoryMetricReaderLayer, meterLayer, ContextStorage.fiberRef, observableRefLayer)

  private val contextualSpec =
    suite("contextual")(
      test("counter") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader        <- ZIO.service[InMemoryMetricReader]
            tracing       <- ZIO.service[Tracing]
            counter       <- meter.counter("test_counter")
            _             <- counter.inc() @@ tracing.aspects.span("counter_span")
            span          <- TracingTest.getFinishedSpans.map(_.head)
            metric         = reader.collectAllMetrics().asScala.toList.head
            metricPoint    = metric.getLongSumData().getPoints().asScala.head
            metricExemplar = metricPoint.getExemplars().asScala.toList.head
            metricSpanId   = metricExemplar.getSpanContext().getSpanId()
            metricTraceId  = metricExemplar.getSpanContext().getTraceId()
          } yield assertTrue(
            metricSpanId == span.getSpanId(),
            metricTraceId == span.getTraceId()
          )
        }
      }
    ).provide(inMemoryMetricReaderLayer, meterLayer, ContextStorage.fiberRef, TracingTest.tracingMockLayer)

}
