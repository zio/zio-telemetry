package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import zio._
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.test.{TestEnvironment, ZIOSpecDefault, _}
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.metrics.MeterTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.Otel

object MeterTest extends ZIOSpecDefault {

  val instrumentationScopeName = "MeterTest"

  val otelLayer: RLayer[MeterTestkit with ContextStorage, OpenTelemetry] = {
    val meterProvider = ZLayer {
      for {
        meterTestkit <- ZIO.service[MeterTestkit]
      } yield meterTestkit.unsafe.getMeterProvider
    }

    meterProvider.flatMap { env =>
      OpenTelemetryTestkit.sdk(meterProvider = env.getDynamic[SdkMeterProvider])
    }
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
        contextualSpec,
        logAnnotatedSpec,
        zioMetricsSpec
      )
    )

  private val normalSpec =
    suite("normal")(
      test("counter") {

        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName)
          counter         <- meter.counter("test_counter")
          attributes       = Attributes(Attribute.long("attr_counter", 3L))
          _               <- counter.add(12, attributes)
          _               <- counter.inc(attributes)
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == 13L,
          metricAttributes == attributes
        )
      },
      test("upDownCounter") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName)
          counter         <- meter.upDownCounter("test_up_down_counter")
          attributes       = Attributes(Attribute.boolean("attr_up_down_counter", value = false))
          _               <- counter.add(5, attributes)
          _               <- counter.inc(attributes)
          _               <- counter.dec(attributes)
          _               <- counter.dec(attributes)
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == 4L,
          metricAttributes == attributes
        )
      },
      test("gauge") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName)
          gauge           <- meter.gauge("test_gauge")
          attributes       = Attributes(Attribute.boolean("attr_gauge", value = false))
          _               <- gauge.set(10.0, attributes)
          _               <- gauge.set(20.0, attributes)
          _               <- gauge.set(-5.6, attributes)
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getDoubleGaugeData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == -5.6,
          metricAttributes == attributes
        )
      },
      test("histogram") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName)
          histogram       <- meter.histogram("test_histogram")
          attributes       = Attributes(Attribute.double("attr_historgram", 12.3))
          _               <- histogram.record(2.1, attributes)
          _               <- histogram.record(3.3, attributes)
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getHistogramData.getPoints.asScala.toList.head
          metricSum        = metricPoint.getSum
          metricMin        = metricPoint.getMin
          metricMax        = metricPoint.getMax
          metricCount      = metricPoint.getCount
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricSum == 5.4,
          metricMin == 2.1,
          metricMax == 3.3,
          metricCount == 2,
          metricAttributes == attributes
        )
      },
      test("observableCounter") {
        ZIO.scoped(
          for {
            meterTestkit <- ZIO.service[MeterTestkit]
            meter        <- meterTestkit.getMeter(instrumentationScopeName)
            ref          <- ZIO.service[Ref[Long]]
            _            <- meter.observableCounter("obs") { om =>
                              for {
                                v <- ref.get
                                _ <- om.record(v)
                              } yield ()
                            }
            _            <- TestClock.adjust(13.seconds)
            metric       <- meterTestkit.collectMetrics.map(_.head)
            metricPoint   = metric.getLongSumData.getPoints.asScala.toList.head
            metricValue   = metricPoint.getValue
          } yield assertTrue(metricValue == 14L)
        )
      },
      test("zio log annotations are not included when turned off") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName)
          counter         <- meter.counter("test_counter")
          _               <- ZIO.logAnnotate("zio", "annotation") {
                               counter.inc()
                             }
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == 1L,
          metricAttributes == Attributes.empty
        )
      }
    ).provide(MeterTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef, observableRefLayer)

  private val contextualSpec =
    suite("contextual")(
      test("counter") {
        for {
          meterTestkit  <- ZIO.service[MeterTestkit]
          tracerTestkit <- ZIO.service[TracerTestkit]
          meter         <- meterTestkit.getMeter(instrumentationScopeName)
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          counter       <- meter.counter("test_counter")
          _             <- counter.inc() @@ tracer.aspects.span("counter_span")
          span          <- tracerTestkit.getFinishedSpans.map(_.head)
          metric        <- meterTestkit.collectMetrics.map(_.head)
          metricPoint    = metric.getLongSumData.getPoints.asScala.head
          metricExemplar = metricPoint.getExemplars.asScala.toList.head
          metricSpanId   = metricExemplar.getSpanContext.getSpanId
          metricTraceId  = metricExemplar.getSpanContext.getTraceId
        } yield assertTrue(
          metricSpanId == span.spanId,
          metricTraceId == span.traceId
        )

      }
    ).provide(MeterTestkit.inMemory, TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val logAnnotatedSpec =
    suite("log annotated")(
      test("new attributes") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName, logAnnotated = true)
          counter         <- meter.counter("test_counter")
          _               <- ZIO.logAnnotate("zio", "annotation") {
                               counter.inc()
                             }
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == 1L,
          metricAttributes == Attributes(Attribute.string("zio", "annotation"))
        )

      },
      test("instrumented attributes override log annotated") {
        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          meter           <- meterTestkit.getMeter(instrumentationScopeName, logAnnotated = true)
          counter         <- meter.counter("test_counter")
          _               <- ZIO.logAnnotate("zio", "annotation") {
                               counter.inc(Attributes(Attribute.string("zio", "annotation2")))
                             }
          metric          <- meterTestkit.collectMetrics.map(_.head)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(
          metricValue == 1L,
          metricAttributes == Attributes(Attribute.string("zio", "annotation2"))
        )
      }
    ).provide(MeterTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // TODO: add test case for Metric.frequency
  private val zioMetricsSpec =
    suite("ZIO metrics integration")(
      test("counter") {
        val counter = Metric.counter("test_counter").tagged("zio", "counter")

        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          _               <- counter.incrementBy(3L)
          metric          <- meterTestkit.collectMetrics.map(_.find(_.getName == "test_counter").get)
          metricPoint      = metric.getLongSumData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(metricValue == 3, metricAttributes == Attributes(Attribute.string("zio", "counter")))
      },
      test("gauge") {
        val gauge = Metric.gauge("test_gauge").tagged("zio", "gauge")

        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          _               <- gauge.set(10.2)
          metric          <- meterTestkit.collectMetrics.map(_.find(_.getName == "test_gauge").get)
          metricPoint      = metric.getDoubleGaugeData.getPoints.asScala.toList.head
          metricValue      = metricPoint.getValue
          metricAttributes = metricPoint.getAttributes
        } yield assertTrue(metricValue == 10.2, metricAttributes == Attributes(Attribute.string("zio", "gauge")))
      },
      test("histogram") {
        val histogram = Metric.histogram("test_histogram", Boundaries.fromChunk(Chunk(1, 2, 3)))

        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          _               <- histogram.update(3.0)
          _               <- histogram.update(1.5)
          metric          <- meterTestkit.collectMetrics.map(_.find(_.getName == "test_histogram").get)
          metricPoint      = metric.getHistogramData.getPoints.asScala.toList.head
          metricMaxValue   = metricPoint.getMax
          metricMinValue   = metricPoint.getMin
          metricCountValue = metricPoint.getCount
          metricBoundaries = metricPoint.getBoundaries.asScala.map(_.toDouble).toSeq
        } yield assertTrue(
          metricMaxValue == 3.0,
          metricMinValue == 1.5,
          metricCountValue == 2,
          metricBoundaries == Seq(1.0, 2.0, 3.0)
        )
      },
      test("timer") {
        val timer = Metric.timer("test_timer", ChronoUnit.SECONDS, Chunk(1.0, 2.0, 3.0))

        for {
          meterTestkit    <- ZIO.service[MeterTestkit]
          _               <- timer.update(Duration.fromSeconds(1))
          _               <- timer.update(Duration.fromSeconds(2))
          metric          <- meterTestkit.collectMetrics.map(_.find(_.getName == "test_timer").get)
          metricPoint      = metric.getHistogramData.getPoints.asScala.toList.head
          metricMaxValue   = metricPoint.getMax
          metricMinValue   = metricPoint.getMin
          metricCountValue = metricPoint.getCount
          metricBoundaries = metricPoint.getBoundaries.asScala.map(_.toDouble).toSeq
        } yield assertTrue(
          metricMaxValue == 2,
          metricMinValue == 1,
          metricCountValue == 2,
          metricBoundaries == Seq(1.0, 2.0, 3.0)
        )
      }
    ).provide(
      MeterTestkit.inMemory,
      OpenTelemetryTestkit.ctxStorageZioFiberRef,
      otelLayer,
      Otel.zioMetrics("MeterTest")
    )

}
