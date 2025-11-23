package zio.telemetry.opentelemetry.zio.logging

import zio.Runtime.removeDefaultLoggers
import zio._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

import scala.collection.mutable

object TelemetryLogFormatsTest extends ZIOSpecDefault {

  val instrumentationScopeName = "TelemetryLogFormatsTest"

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suiteAll("opentelemetry-zio-logging LogFormats") {
      test("SpanId and traceId are extracted") {
        val logs = mutable.Buffer[String]()

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          logFormats    <- ZIO.service[LogFormats]
          format         = logFormats.spanIdLabel |-| logFormats.traceIdLabel
          zLogger        = format.toLogger.map(logs.append(_))
          _             <- ZIO.logInfo("TEST").withLogger(zLogger) @@ tracer.aspects.span("Span") @@ tracer.aspects.root("Root")
          spans         <- tracerTestkit.getFinishedSpans
          child          = spans.find(_.name == "Span").get
          log            = logs.head
        } yield assertTrue(log == s"spanId=${child.spanId} traceId=${child.traceId}")
      }
    }.provide(
      removeDefaultLoggers,
      // tracerMockLayer(),
      ZioLogging.logFormats,
      TracerTestkit.inMemory,
      OpenTelemetryTestkit.sdk(),
      OpenTelemetryTestkit.ctxStorageZioFiberRef
    )

}
