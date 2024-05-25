package zio.telemetry.opentelemetry.zio.logging

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio.Runtime.removeDefaultLoggers
import zio.logging.LogFormat.label
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Scope, UIO, ULayer, URLayer, ZEnvironment, ZIO, ZLayer}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object TelemetryLogFormatsSpec extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, Tracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with Tracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

  def tracingMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracing with InMemorySpanExporter with Tracer] =
    inMemoryTracerLayer >>> (Tracing.live(logAnnotated) ++ inMemoryTracerLayer)

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suiteAll("opentelemetry-zio-logging LogFormats") {
      test("SpanId and traceId are extracted") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._
          val logs = mutable.Buffer[String]()

          for {
            contextStorage <- ZIO.service[ContextStorage]
            format          = label("spanId", TelemetryLogFormats.spanId(contextStorage)) |-| label(
                                "traceId",
                                TelemetryLogFormats.traceId(contextStorage)
                              )
            zLogger         = format.toLogger.map(logs.append(_))
            _              <- zio.ZIO.logInfo("TEST").withLogger(zLogger) @@ span("Span") @@ root("Root")

            spans <- getFinishedSpans
            child  = spans.find(_.getName == "Span").get
            log    = logs.head
          } yield assertTrue(log == s"spanId=${child.getSpanId} traceId=${child.getTraceId}")
        }
      }
    }.provide(removeDefaultLoggers, tracingMockLayer(), ContextStorage.fiberRef)

}
