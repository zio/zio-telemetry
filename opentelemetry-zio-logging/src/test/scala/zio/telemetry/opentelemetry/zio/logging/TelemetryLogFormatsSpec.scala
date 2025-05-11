package zio.telemetry.opentelemetry.zio.logging

import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio.Runtime.removeDefaultLoggers
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.trace.Tracer
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Scope, UIO, ULayer, URLayer, ZEnvironment, ZIO, ZLayer}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object TelemetryLogFormatsSpec extends ZIOSpecDefault {

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

  def tracerMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[OpenTelemetry, Tracer with InMemorySpanExporter with JTracer] = {
    val tracerLayer = ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        jtracer       <- ZIO.service[JTracer]
        tracer         = Tracer.make(jtracer, openTelemetry.ctxStorage, logAnnotated)
      } yield tracer
    }

    inMemoryTracerLayer >>> (tracerLayer ++ inMemoryTracerLayer)
  }

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suiteAll("opentelemetry-zio-logging LogFormats") {
      test("SpanId and traceId are extracted") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._
          val logs = mutable.Buffer[String]()

          for {
            logFormats <- ZIO.service[LogFormats]
            format      = logFormats.spanIdLabel |-| logFormats.traceIdLabel
            zLogger     = format.toLogger.map(logs.append(_))
            _          <- ZIO.logInfo("TEST").withLogger(zLogger) @@ span("Span") @@ root("Root")
            spans      <- getFinishedSpans
            child       = spans.find(_.getName == "Span").get
            log         = logs.head
          } yield assertTrue(log == s"spanId=${child.getSpanId} traceId=${child.getTraceId}")
        }
      }
    }.provide(
      OpenTelemetry.noop(),
      removeDefaultLoggers,
      tracerMockLayer(),
      ZioLogging.logFormats
    )

}
