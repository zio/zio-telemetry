package zio.telemetry.opencensus

import io.opencensus.trace.`export`.SpanData
import io.opencensus.trace.samplers.Samplers
import io.opencensus.trace.{ SpanId, Tracer, Tracing => OTracing }
import zio._
import zio.test.Assertion._
import zio.test._

object OpenCensusTracingTest extends ZIOSpecDefault {

  private def getFinishedSpans =
    ZIO.serviceWithZIO[Ref[List[SpanData]]] { x =>
      OTracing.getExportComponent.shutdown()
      x.get
    }

  val exporterTracer: UIO[(Ref[List[SpanData]], Tracer)] =
    InMemoryExporter.register().map { exporter =>
      val traceConfig       = OTracing.getTraceConfig
      val activeTraceParams = traceConfig.getActiveTraceParams
      traceConfig.updateActiveTraceParams(activeTraceParams.toBuilder.setSampler(Samplers.alwaysSample).build)
      exporter -> OTracing.getTracer
    }

  val exporterTracerLayer: ULayer[Ref[List[SpanData]] with Tracer] = ZLayer.fromZIOEnvironment(exporterTracer.map {
    case (exporter, tracer) =>
      ZEnvironment(exporter).add(tracer)
  })

  val customLayer: ULayer[Ref[List[SpanData]] with Tracer with Tracing] =
    exporterTracerLayer ++ (exporterTracerLayer >>> Tracing.live)

  def spec =
    suite("zio opencensus")(
      suite("Tracing")(
        spansSpec
      )
    )

  private def spansSpec =
    suite("spans")(test("span") {
      ZIO
        .serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- ZIO.unit @@ span("Child") @@ span("Root")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, SpanId](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getContext.getSpanId)
                )
              )
            )
        }
        .provideLayer(customLayer)
    })
}
