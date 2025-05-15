package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.{InMemoryMetricReader, InMemorySpanExporter}
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import zio.telemetry.opentelemetry.trace.Tracer
import zio.test.{TestEnvironment, ZIOSpecDefault, _}

import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._

object MeterTest extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, JTracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with JTracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

  val inMemoryMetricReaderLayer: ZLayer[Any, Nothing, InMemoryMetricReader] =
    ZLayer(ZIO.succeed(InMemoryMetricReader.create()))

  val inMemoryMeterProvider: ULayer[SdkMeterProvider] = {
    val meterProviderLayer =
      ZLayer {
        for {
          metricReader  <- ZIO.service[InMemoryMetricReader]
          meterProvider <- ZIO.succeed(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        } yield meterProvider
      }

    inMemoryMetricReaderLayer >>> meterProviderLayer
  }

  val otelLayer: RLayer[SdkMeterProvider, OpenTelemetry] =
    ZLayer.scoped {
      for {
        ctxStorage    <- ContextStorage.zioFiberRefScoped
        meterProvider <- ZIO.service[SdkMeterProvider]
        underlying    <- ZIO.fromAutoCloseable(
                           ZIO.succeed(
                             OpenTelemetrySdk
                               .builder()
                               .setMeterProvider(meterProvider)
                               .build
                           )
                         )
      } yield new OpenTelemetry.OpenTelemetrySdk(ctxStorage, underlying)
    }

  def ctxStorageLayer: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def tracerMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracer with InMemorySpanExporter with JTracer] =
    inMemoryTracerLayer >>> (tracerLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracerLiveLayer(logAnnotated: Boolean = false): URLayer[JTracer with ContextStorage, Tracer] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        jtracer    <- ZIO.service[JTracer]
        tracer      = Tracer.make(jtracer, ctxStorage, logAnnotated)
      } yield tracer
    }

  def meterLayer(
    logAnnotated: Boolean = false
  ): ZLayer[ContextStorage, Nothing, Meter] = {
    val meterLayer = ZLayer {
      for {
        ctxStorage    <- ZIO.service[ContextStorage]
        meterProvider <- ZIO.service[SdkMeterProvider]
        jmeter        <- ZIO.succeed(meterProvider.get("MeterTest"))
        builder        = Instrument.Builder.make(jmeter, ctxStorage, logAnnotated)
        meter          = Meter.make(builder)
      } yield meter
    }

    inMemoryMeterProvider >>> meterLayer
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

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  override def spec: Spec[TestEnvironment with Scope, Any] =
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
      },
      test("zio log annotations are not included when turned off") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counter("test_counter")
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
    ).provide(inMemoryMetricReaderLayer, meterLayer(), ctxStorageLayer, observableRefLayer)

  private val contextualSpec =
    suite("contextual")(
      test("counter") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader        <- ZIO.service[InMemoryMetricReader]
            tracer        <- ZIO.service[Tracer]
            counter       <- meter.counter("test_counter")
            _             <- counter.inc() @@ tracer.aspects.span("counter_span")
            span          <- getFinishedSpans.map(_.head)
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
    ).provide(inMemoryMetricReaderLayer, meterLayer(), ctxStorageLayer, tracerMockLayer())

  private val logAnnotatedSpec =
    suite("log annotated")(
      test("new attributes") {
        ZIO.serviceWithZIO[Meter] { meter =>
          for {
            reader          <- ZIO.service[InMemoryMetricReader]
            counter         <- meter.counter("test_counter")
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
            counter         <- meter.counter("test_counter")
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
    ).provide(inMemoryMetricReaderLayer, meterLayer(logAnnotated = true), ctxStorageLayer)

  private val zioMetricsSpec =
    suite("ZIO metrics integration")(
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
    ).provide(
      inMemoryMeterProvider,
      otelLayer,
      inMemoryMetricReaderLayer,
      OpenTelemetry.zioMetrics("MeterTest")
    )

}
