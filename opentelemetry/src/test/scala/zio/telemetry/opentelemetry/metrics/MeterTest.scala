package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import zio._
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import zio.telemetry.opentelemetry.tracing.{Tracing, TracingTest}
import zio.test.{TestEnvironment, ZIOSpecDefault, _}

import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._

object MeterTest extends ZIOSpecDefault {

  val inMemoryMetricReaderLayer: ZLayer[Any, Nothing, InMemoryMetricReader] =
    ZLayer(ZIO.succeed(InMemoryMetricReader.create()))

  def meterLayer(
    logAnnotated: Boolean = false
  ): ZLayer[InMemoryMetricReader & ContextStorage, Nothing, Meter & Instrument.Builder] = {
    val jmeter  = ZLayer {
      for {
        metricReader  <- ZIO.service[InMemoryMetricReader]
        meterProvider <- ZIO.succeed(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        meter         <- ZIO.succeed(meterProvider.get("MeterTest"))
      } yield meter
    }
    val builder = jmeter >>> Instrument.Builder.live(logAnnotated)

    builder >+> Meter.live
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

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("zio opentelemetry")(
      suite("Meter")(
        normalSpec,
        contextualSpec,
        logAnnotatedSpec,
        zioMetricsSpec
      )
    )

  private val normalSpec =
    suite("normal")(
      test("counterLong") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterLong("test_counter")
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
      test("counterDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterDouble("test_double_counter")
            attributes       = Attributes(Attribute.long("attr1", 3L))
            _               <- counter.add(0.25, attributes)
            _               <- counter.inc(attributes)
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getDoubleSumData().getPoints().asScala.toList.head
            metricValue      = metricPoint.getValue()
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 1.25,
            metricAttributes == attributes
          )
        }
      },
      test("upDownCounterLong") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.upDownCounterLong("test_up_down_counter")
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
      test("histogramDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            histogram       <- meter.histogramDouble("test_histogram")
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
      test("observableCounterLong") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader     <- ZIO.service[InMemoryMetricReader]
              ref        <- ZIO.service[Ref[Long]]
              _          <- meter.observableCounterLong("obs") { om =>
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
      },
      test("numeric synchronous instruments export the expected measurements") {
        for {
          meter         <- ZIO.service[Meter]
          reader        <- ZIO.service[InMemoryMetricReader]
          longUpDown    <- meter.upDownCounterLong("long_up_down")
          _             <- longUpDown.add(Long.MaxValue)
          doubleUpDown  <- meter.upDownCounterDouble("double_up_down")
          _             <- doubleUpDown.add(-1.25)
          _             <- doubleUpDown.inc()
          longHistogram <- meter.histogramLong("long_histogram", boundaries = Some(Chunk(2L, 4L)))
          _             <- longHistogram.record(3L)
          dblHistogram  <- meter.histogramDouble("double_histogram", boundaries = Some(Chunk(1.25, 2.75)))
          _             <- dblHistogram.record(1.5)
          longGauge     <- meter.gaugeLong("long_gauge")
          attributes     = Attributes(Attribute.string("source", "test"))
          _             <- longGauge.set(Long.MaxValue, attributes)
          doubleGauge   <- meter.gaugeDouble("double_gauge")
          _             <- doubleGauge.set(1.25)
          _             <- doubleGauge.set(2.75)
          metrics        = reader.collectAllMetrics().asScala.map(metric => metric.getName -> metric).toMap
          longBuckets    = metrics("long_histogram").getHistogramData.getPoints.asScala.head.getCounts.asScala
                             .map(_.longValue())
                             .toList
          longGaugePoint = metrics("long_gauge").getLongGaugeData.getPoints.asScala.head
          doublePoint    = metrics("double_histogram").getHistogramData.getPoints.asScala.head
        } yield assertTrue(
          metrics("long_up_down").getLongSumData.getPoints.asScala.head.getValue == Long.MaxValue,
          metrics("double_up_down").getDoubleSumData.getPoints.asScala.head.getValue == -0.25,
          longBuckets == List(0L, 1L, 0L),
          doublePoint.getCounts.asScala.map(_.longValue()).toList == List(0L, 1L, 0L),
          doublePoint.getSum == 1.5,
          longGaugePoint.getValue == Long.MaxValue,
          longGaugePoint.getAttributes == attributes,
          metrics("double_gauge").getDoubleGaugeData.getPoints.asScala.head.getValue == 2.75
        )
      },
      test("numeric observable instruments export measurements while their scope is open") {
        for {
          meter  <- ZIO.service[Meter]
          reader <- ZIO.service[InMemoryMetricReader]
          result <- ZIO.scoped[Any] {
                      for {
                        _      <- meter.observableCounterLong("long_counter")(_.record(Long.MaxValue))
                        _      <- meter.observableCounterDouble("double_counter")(_.record(1.25))
                        _      <- meter.observableUpDownCounterLong("long_up_down")(_.record(Long.MinValue))
                        _      <- meter.observableUpDownCounterDouble("double_up_down")(_.record(-1.25))
                        _      <- meter.observableGaugeLong("long_gauge")(_.record(Long.MaxValue))
                        _      <- meter.observableGaugeDouble("double_gauge")(_.record(2.75))
                        metrics = reader.collectAllMetrics().asScala.map(metric => metric.getName -> metric).toMap
                      } yield assertTrue(
                        metrics("long_counter").getLongSumData.getPoints.asScala.head.getValue == Long.MaxValue,
                        metrics("double_counter").getDoubleSumData.getPoints.asScala.head.getValue == 1.25,
                        metrics("long_up_down").getLongSumData.getPoints.asScala.head.getValue == Long.MinValue,
                        metrics("double_up_down").getDoubleSumData.getPoints.asScala.head.getValue == -1.25,
                        metrics("long_gauge").getLongGaugeData.getPoints.asScala.head.getValue == Long.MaxValue,
                        metrics("double_gauge").getDoubleGaugeData.getPoints.asScala.head.getValue == 2.75
                      )
                    }
        } yield result && assertTrue(reader.collectAllMetrics().isEmpty)
      },
      test("zio log annotations are not included when turned off") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterLong("test_counter")
            _               <- ZIO.logAnnotate("zio", "annotation") {
                                 counter.inc()
                               }
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
            metricValue      = metricPoint.getValue
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 1L,
            metricAttributes == Attributes.empty
          )
        }
      }
    ).provide(inMemoryMetricReaderLayer, meterLayer(), ContextStorage.fiberRef, observableRefLayer)

  private val contextualSpec =
    suite("contextual")(
      test("counter") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader        <- ZIO.service[InMemoryMetricReader]
            tracing       <- ZIO.service[Tracing]
            counter       <- meter.counterLong("test_counter")
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
    ).provide(inMemoryMetricReaderLayer, meterLayer(), ContextStorage.fiberRef, TracingTest.tracingMockLayer())

  private val logAnnotatedSpec =
    suite("log annotated")(
      test("new attributes") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterLong("test_counter")
            _               <- ZIO.logAnnotate("zio", "annotation") {
                                 counter.inc()
                               }
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
            metricValue      = metricPoint.getValue
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 1L,
            metricAttributes == Attributes(Attribute.string("zio", "annotation"))
          )
        }
      },
      test("instrumented attributes override log annotated") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterLong("test_counter")
            _               <- ZIO.logAnnotate("zio", "annotation") {
                                 counter.inc(Attributes(Attribute.string("zio", "annotation2")))
                               }
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
            metricValue      = metricPoint.getValue
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricValue == 1L,
            metricAttributes == Attributes(Attribute.string("zio", "annotation2"))
          )
        }
      }
    ).provide(inMemoryMetricReaderLayer, meterLayer(logAnnotated = true), ContextStorage.fiberRef)

  private val zioMetricsSpec =
    suite("ZIO metrics integration")(
      test("counter preserves fractional increments") {
        val counter = Metric.counterDouble("test_fractional_counter")

        for {
          reader <- ZIO.service[InMemoryMetricReader]
          _      <- counter.update(0.25)
          _      <- counter.update(0.25)
          metric  = reader.collectAllMetrics().asScala.find(_.getName == "test_fractional_counter").get
          values  = metric.getDoubleSumData.getPoints.asScala.map(_.getValue).toList
        } yield assertTrue(values == List(0.5))
      },
      test("frequency exports long bucket counts") {
        val frequency = Metric.frequency("test_frequency")

        for {
          reader <- ZIO.service[InMemoryMetricReader]
          _      <- frequency.update("hit")
          metric  = reader.collectAllMetrics().asScala.find(_.getName == "test_frequency").get
          point   = metric.getLongSumData.getPoints.asScala.head
        } yield assertTrue(point.getValue == 1L, point.getAttributes == Attributes(Attribute.string("bucket", "hit")))
      },
      test("histogram boundaries should be passed to OTEL") {
        val histogram = Metric.histogram("test_histogram", Boundaries.fromChunk(Chunk(1, 2, 3)))

        for {
          reader     <- ZIO.service[InMemoryMetricReader]
          _          <- histogram.update(2.0)
          metric      = reader.collectAllMetrics().asScala.find(_.getName == "test_histogram").get
          metricPoint = metric.getHistogramData().getPoints().asScala.toList.head
          boundaries  = metricPoint.getBoundaries.asScala.map(_.toDouble).toSeq
        } yield assertTrue(boundaries == Seq(1.0, 2.0, 3.0))
      },
      test("timer boundaries should be passed to OTEL") {
        val timer = Metric.timer("test_timer", ChronoUnit.SECONDS, Chunk(1.0, 2.0, 3.0))

        for {
          reader     <- ZIO.service[InMemoryMetricReader]
          _          <- timer.update(Duration.fromSeconds(2))
          metric      = reader.collectAllMetrics().asScala.find(_.getName == "test_timer").get
          metricPoint = metric.getHistogramData().getPoints().asScala.toList.head
          boundaries  = metricPoint.getBoundaries.asScala.map(_.toDouble).toSeq
        } yield assertTrue(boundaries == Seq(1.0, 2.0, 3.0))
      }
    ).provide(inMemoryMetricReaderLayer, meterLayer(), ContextStorage.fiberRef, OpenTelemetry.zioMetrics)

}
