package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import zio._
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.metrics.MeterTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import zio.telemetry.opentelemetry.{OpenTelemetry, Otel, testkit}
import zio.test.{TestEnvironment, ZIOSpecDefault, _}

import java.time.temporal.ChronoUnit

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
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName)
          counter      <- meter.counter("test_counter")
          attributes    = Attributes(Attribute.long("attr_counter", 3L))
          _            <- counter.add(12, attributes)
          _            <- counter.inc(attributes)
          metric       <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 13L,
          metricPoint.attributes == testkit.common.Attributes(attributes)
        )
      },
      test("upDownCounter") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName)
          counter      <- meter.upDownCounter("test_up_down_counter")
          attributes    = Attributes(Attribute.boolean("attr_up_down_counter", value = false))
          _            <- counter.add(5, attributes)
          _            <- counter.inc(attributes)
          _            <- counter.dec(attributes)
          _            <- counter.dec(attributes)
          metric       <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 4L,
          metricPoint.attributes == testkit.common.Attributes(attributes)
        )
      },
      test("gauge") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName)
          gauge        <- meter.gauge("test_gauge")
          attributes    = Attributes(Attribute.boolean("attr_gauge", value = false))
          _            <- gauge.set(10.0, attributes)
          _            <- gauge.set(20.0, attributes)
          _            <- gauge.set(-5.6, attributes)
          metric       <- meterTestkit.collectGaugeMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == -5.6,
          metricPoint.attributes == testkit.common.Attributes(attributes)
        )
      },
      test("histogram") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName)
          histogram    <- meter.histogram("test_histogram")
          attributes    = Attributes(Attribute.double("attr_historgram", 12.3))
          _            <- histogram.record(2.1, attributes)
          _            <- histogram.record(3.3, attributes)
          metric       <- meterTestkit.collectHistogramMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.sum == 5.4,
          metricPoint.min == 2.1,
          metricPoint.max == 3.3,
          metricPoint.count == 2,
          metricPoint.attributes == testkit.common.Attributes(attributes)
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
            metric       <- meterTestkit.collectCounterMetrics.map(_.head)
            metricPoint   = metric.points.head
          } yield assertTrue(metricPoint.value == 14L)
        )
      },
      test("zio log annotations are not included when turned off") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName)
          counter      <- meter.counter("test_counter")
          _            <- ZIO.logAnnotate("zio", "annotation") {
                            counter.inc()
                          }
          metric       <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 1L,
          metricPoint.attributes == testkit.common.Attributes(Attributes.empty)
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
          metric        <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint    = metric.points.head
          metricExemplar = metricPoint.exemplars.head
        } yield assertTrue(
          metricExemplar.spanContext.spanId == span.spanId,
          metricExemplar.spanContext.traceId == span.traceId
        )

      }
    ).provide(MeterTestkit.inMemory, TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val logAnnotatedSpec =
    suite("log annotated")(
      test("new attributes") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName, logAnnotated = true)
          counter      <- meter.counter("test_counter")
          _            <- ZIO.logAnnotate("zio", "annotation") {
                            counter.inc()
                          }
          metric       <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 1L,
          metricPoint.attributes == testkit.common.Attributes(Attributes(Attribute.string("zio", "annotation")))
        )

      },
      test("instrumented attributes override log annotated") {
        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          meter        <- meterTestkit.getMeter(instrumentationScopeName, logAnnotated = true)
          counter      <- meter.counter("test_counter")
          _            <- ZIO.logAnnotate("zio", "annotation") {
                            counter.inc(Attributes(Attribute.string("zio", "annotation2")))
                          }
          metric       <- meterTestkit.collectCounterMetrics.map(_.head)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 1L,
          metricPoint.attributes == testkit.common.Attributes(Attributes(Attribute.string("zio", "annotation2")))
        )
      }
    ).provide(MeterTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // TODO: add test case for Metric.frequency
  private val zioMetricsSpec =
    suite("ZIO metrics integration")(
      test("counter") {
        val counter = Metric.counter("test_counter").tagged("zio", "counter")

        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          _            <- counter.incrementBy(3L)
          metric       <- meterTestkit.collectCounterMetrics.map(_.find(_.name == "test_counter").get)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 3,
          metricPoint.attributes == testkit.common.Attributes(Attributes(Attribute.string("zio", "counter")))
        )
      },
      test("gauge") {
        val gauge = Metric.gauge("test_gauge").tagged("zio", "gauge")

        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          _            <- gauge.set(10.2)
          metric       <- meterTestkit.collectGaugeMetrics.map(_.find(_.name == "test_gauge").get)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.value == 10.2,
          metricPoint.attributes == testkit.common.Attributes(Attributes(Attribute.string("zio", "gauge")))
        )
      },
      test("histogram") {
        val histogram = Metric.histogram("test_histogram", Boundaries.fromChunk(Chunk(1, 2, 3)))

        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          _            <- histogram.update(3.0)
          _            <- histogram.update(1.5)
          metric       <- meterTestkit.collectHistogramMetrics.map(_.find(_.name == "test_histogram").get)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.max == 3.0,
          metricPoint.min == 1.5,
          metricPoint.count == 2,
          metricPoint.boundaries == List(1.0, 2.0, 3.0)
        )
      },
      test("timer") {
        val timer = Metric.timer("test_timer", ChronoUnit.SECONDS, Chunk(1.0, 2.0, 3.0))

        for {
          meterTestkit <- ZIO.service[MeterTestkit]
          _            <- timer.update(Duration.fromSeconds(1))
          _            <- timer.update(Duration.fromSeconds(2))
          metric       <- meterTestkit.collectHistogramMetrics.map(_.find(_.name == "test_timer").get)
          metricPoint   = metric.points.head
        } yield assertTrue(
          metricPoint.max == 2,
          metricPoint.min == 1,
          metricPoint.count == 2,
          metricPoint.boundaries == List(1.0, 2.0, 3.0)
        )
      }
    ).provide(
      MeterTestkit.inMemory,
      OpenTelemetryTestkit.ctxStorageZioFiberRef,
      otelLayer,
      Otel.zioMetrics("MeterTest")
    )

}
