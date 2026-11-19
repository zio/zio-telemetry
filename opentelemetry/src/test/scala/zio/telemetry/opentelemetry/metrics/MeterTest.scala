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
            counter         <- meter.counterLong("test_long_counter")
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
            counter         <- meter.upDownCounterLong("test_long_up_down_counter")
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
      test("upDownCounterDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader    <- ZIO.service[InMemoryMetricReader]
            counter   <- meter.upDownCounterDouble("test_double_up_down_counter")
            attributes = Attributes(Attribute.boolean("attr2", value = false))
            _         <- counter.add(-1.25, attributes)
            _         <- counter.inc(attributes)
            _         <- counter.dec(attributes)
            _         <- counter.dec(attributes)
            metric     = reader.collectAllMetrics().asScala.head
            point      = metric.getDoubleSumData().getPoints().asScala.head
          } yield assertTrue(point.getValue() == -2.25, point.getAttributes() == attributes)
        }
      },
      test("histogramLong") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader    <- ZIO.service[InMemoryMetricReader]
            histogram <- meter.histogramLong("test_long_histogram", boundaries = Some(Chunk(2L, 4L)))
            _         <- histogram.record(3L)
            metric     = reader.collectAllMetrics().asScala.head
            point      = metric.getHistogramData().getPoints().asScala.head
            counts     = point.getCounts().asScala.map(_.longValue()).toList
          } yield assertTrue(point.getSum() == 3.0, counts == List(0L, 1L, 0L))
        }
      },
      test("histogramDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            histogram       <- meter.histogramDouble("test_histogram", boundaries = Some(Chunk(1.25, 2.75)))
            attributes       = Attributes(Attribute.double("attr3", 12.3))
            _               <- histogram.record(2.1, attributes)
            _               <- histogram.record(3.3, attributes)
            metric           = reader.collectAllMetrics().asScala.toList.head
            metricPoint      = metric.getHistogramData().getPoints().asScala.toList.head
            metricSum        = metricPoint.getSum()
            metricMin        = metricPoint.getMin()
            metricMax        = metricPoint.getMax()
            metricCount      = metricPoint.getCount()
            bucketCounts     = metricPoint.getCounts().asScala.map(_.longValue()).toList
            metricAttributes = metricPoint.getAttributes()
          } yield assertTrue(
            metricSum == 5.4,
            metricMin == 2.1,
            metricMax == 3.3,
            metricCount == 2,
            bucketCounts == List(0L, 1L, 1L),
            metricAttributes == attributes
          )
        }
      },
      test("gaugeLong") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader    <- ZIO.service[InMemoryMetricReader]
            gauge     <- meter.gaugeLong("test_long_gauge")
            attributes = Attributes(Attribute.string("source", "test"))
            _         <- gauge.set(1L, attributes)
            _         <- gauge.set(Long.MaxValue, attributes)
            metric     = reader.collectAllMetrics().asScala.head
            point      = metric.getLongGaugeData().getPoints().asScala.head
          } yield assertTrue(point.getValue() == Long.MaxValue, point.getAttributes() == attributes)
        }
      },
      test("gaugeDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader    <- ZIO.service[InMemoryMetricReader]
            gauge     <- meter.gaugeDouble("test_double_gauge")
            attributes = Attributes(Attribute.string("source", "test"))
            _         <- gauge.set(1.25, attributes)
            _         <- gauge.set(2.75, attributes)
            metric     = reader.collectAllMetrics().asScala.head
            point      = metric.getDoubleGaugeData().getPoints().asScala.head
          } yield assertTrue(point.getValue() == 2.75, point.getAttributes() == attributes)
        }
      },
      test("observableCounterLong") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader     <- ZIO.service[InMemoryMetricReader]
              ref        <- ZIO.service[Ref[Long]]
              _          <- meter.observableCounterLong("test_long_observable_counter") { om =>
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
      test("observableCounterDouble") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader <- ZIO.service[InMemoryMetricReader]
            value  <- ZIO.scoped[Any] {
                        for {
                          _     <- meter.observableCounterDouble("test_double_observable_counter")(_.record(1.25))
                          metric = reader.collectAllMetrics().asScala.head
                          point  = metric.getDoubleSumData().getPoints().asScala.head
                        } yield point.getValue()
                      }
          } yield assertTrue(value == 1.25, reader.collectAllMetrics().isEmpty)
        }
      },
      test("observableUpDownCounterLong") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader <- ZIO.service[InMemoryMetricReader]
              _      <- meter.observableUpDownCounterLong("observableUpDownCounterLong")(_.record(Long.MinValue))
              metric  = reader.collectAllMetrics().asScala.head
              point   = metric.getLongSumData().getPoints().asScala.head
            } yield assertTrue(point.getValue() == Long.MinValue)
          }
        )
      },
      test("observableUpDownCounterDouble") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader <- ZIO.service[InMemoryMetricReader]
              _      <- meter.observableUpDownCounterDouble("observableUpDownCounterDouble")(_.record(-1.25))
              metric  = reader.collectAllMetrics().asScala.head
              point   = metric.getDoubleSumData().getPoints().asScala.head
            } yield assertTrue(point.getValue() == -1.25)
          }
        )
      },
      test("observableGaugeLong") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader <- ZIO.service[InMemoryMetricReader]
              _      <- meter.observableGaugeLong("observableGaugeLong")(_.record(Long.MaxValue))
              metric  = reader.collectAllMetrics().asScala.head
              point   = metric.getLongGaugeData().getPoints().asScala.head
            } yield assertTrue(point.getValue() == Long.MaxValue)
          }
        )
      },
      test("observableGaugeDouble") {
        ZIO.scoped(
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader <- ZIO.service[InMemoryMetricReader]
              _      <- meter.observableGaugeDouble("observableGaugeDouble")(_.record(2.75))
              metric  = reader.collectAllMetrics().asScala.head
              point   = metric.getDoubleGaugeData().getPoints().asScala.head
            } yield assertTrue(point.getValue() == 2.75)
          }
        )
      },
      test("zio log annotations are not included when turned off") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counterLong("test_long_counter")
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
      test("counterLong") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader        <- ZIO.service[InMemoryMetricReader]
            tracing       <- ZIO.service[Tracing]
            counter       <- meter.counterLong("test_long_counter")
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
            counter         <- meter.counterLong("test_long_counter")
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
            counter         <- meter.counterLong("test_long_counter")
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
