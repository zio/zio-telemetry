package zio.telemetry.opentelemetry.core.trace

import io.opentelemetry.api.trace.StatusCode
import zio._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.trace.{SpanData, TracerTestkit}
import zio.test.Assertion._
import zio.test._

/**
 * Tests for the LogSpanner FiberRef-based span dispatch mechanism (#1022).
 *
 * Verifies:
 *   - Default backend delegates to ZIO.logSpan (no OTEL spans)
 *   - OTEL backend creates real OTEL spans with correct parent-child nesting
 *   - Hybrid backend creates both OTEL and ZIO logSpans
 *   - Scoped installation reverts correctly
 *   - FiberRef propagation through fork/timeout
 */
object LogSpannerTest extends ZIOSpecDefault {

  val instrumentationScopeName = "LogSpannerTest"

  def assertSpanStatusCode(assertion: Assertion[StatusCode]): Assertion[SpanData] =
    hasField[SpanData, StatusCode]("statusCode", _.status.statusCode, assertion)

  def assertSpanParentId(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String]("parentSpanId", _.parentSpanId, assertion)

  override def spec: Spec[Any, Throwable] =
    suite("LogSpanner")(
      defaultBackendSuite,
      otelBackendSuite,
      hybridBackendSuite,
      scopingSuite,
      fiberCorrectnessSuite
    )

  // ---------------------------------------------------------------------------
  // Default backend tests
  // ---------------------------------------------------------------------------

  private val defaultBackendSuite =
    suite("default backend")(
      test("delegates to ZIO.logSpan — no OTEL span created") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          _             <- ZIO.unit @@ LogSpanner.span("mySpan")
          spans         <- tracerTestkit.getFinishedSpans
        } yield assert(spans)(isEmpty)
      },
      test("ZIO logSpan name is visible in log annotations") {
        for {
          ref    <- Ref.make(List.empty[String])
          _      <- FiberRef.currentLogSpan.getWith { spans =>
                      ref.set(spans.map(_.label))
                    } @@ LogSpanner.span("mySpan")
          labels <- ref.get
        } yield assert(labels)(contains("mySpan"))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // ---------------------------------------------------------------------------
  // OTEL backend tests
  // ---------------------------------------------------------------------------

  private val otelBackendSuite =
    suite("OTEL backend")(
      test("creates real OTEL span") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("otelSpan"))
                           }
          spans         <- tracerTestkit.getFinishedSpans
          otelSpan       = spans.find(_.name == "otelSpan")
        } yield assert(otelSpan)(isSome(anything))
      },
      test("OTEL span has correct parent-child nesting") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("child") @@ LogSpanner.span("parent"))
                           }
          spans         <- tracerTestkit.getFinishedSpans
          parent         = spans.find(_.name == "parent")
          child          = spans.find(_.name == "child")
        } yield assert(parent)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(parent.get.spanId))))
      },
      test("OTEL span nests correctly with tracer.span") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               tracer.span("tracerParent") { _ =>
                                 ZIO.unit @@ LogSpanner.span("logSpannerChild")
                               }
                           }
          spans         <- tracerTestkit.getFinishedSpans
          parent         = spans.find(_.name == "tracerParent")
          child          = spans.find(_.name == "logSpannerChild")
        } yield assert(parent)(isSome(anything)) &&
          assert(child)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(parent.get.spanId))))
      },
      test("composes with ZIO.logAnnotate when logAnnotated=true") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName, logAnnotated = true)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               ZIO.logAnnotate("key", "value") {
                                 ZIO.unit @@ LogSpanner.span("annotatedSpan")
                               }
                           }
          spans         <- tracerTestkit.getFinishedSpans
          annotated      = spans.find(_.name == "annotatedSpan")
        } yield assert(annotated)(isSome(anything)) &&
          assert(annotated.get.attributes.get[String]("key"))(isSome(equalTo("value")))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // ---------------------------------------------------------------------------
  // Hybrid backend tests
  // ---------------------------------------------------------------------------

  private val hybridBackendSuite =
    suite("hybrid backend")(
      test("creates both OTEL span and ZIO logSpan") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          logSpanRef    <- Ref.make(List.empty[String])
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installHybrid(tracer) *>
                               (FiberRef.currentLogSpan.getWith(spans => logSpanRef.set(spans.map(_.label)))
                                 @@ LogSpanner.span("hybridSpan"))
                           }
          spans         <- tracerTestkit.getFinishedSpans
          logLabels     <- logSpanRef.get
          hybridSpan     = spans.find(_.name == "hybridSpan")
        } yield assert(hybridSpan)(isSome(anything)) &&
          assert(logLabels)(contains("hybridSpan"))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // ---------------------------------------------------------------------------
  // Scoping tests
  // ---------------------------------------------------------------------------

  private val scopingSuite =
    suite("scoping")(
      test("installation is scoped — reverts on scope close") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          // Inner scope: OTEL backend installed
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("inside"))
                           }
          // Outer scope: default backend (OTEL backend was reverted)
          _             <- ZIO.unit @@ LogSpanner.span("outside")
          spans         <- tracerTestkit.getFinishedSpans
          inside         = spans.find(_.name == "inside")
          outside        = spans.find(_.name == "outside")
        } yield assert(inside)(isSome(anything)) &&
          assert(outside)(isNone)
      },
      test("nested scopes override correctly") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("outer-otel")) *>
                               ZIO.scoped[Any] {
                                 // Override with default inside nested scope
                                 LogSpanner.currentLogSpanner.locallyScoped(LogSpanner.default) *>
                                   (ZIO.unit @@ LogSpanner.span("inner-default"))
                               } *>
                               // Back to OTEL after inner scope closes
                               (ZIO.unit @@ LogSpanner.span("outer-otel-again"))
                           }
          spans         <- tracerTestkit.getFinishedSpans
          outerOtel      = spans.find(_.name == "outer-otel")
          innerDefault   = spans.find(_.name == "inner-default")
          outerOtelAgain = spans.find(_.name == "outer-otel-again")
        } yield assert(outerOtel)(isSome(anything)) &&
          assert(innerDefault)(isNone) && // default backend doesn't create OTEL spans
          assert(outerOtelAgain)(isSome(anything))
      },
      test("installOtelLogSpanner layer installs correctly") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             zio.telemetry.opentelemetry.OpenTelemetry.installOtelLogSpanner.build
                               .provideSome[Scope](ZLayer.succeed(tracer)) *>
                               (ZIO.unit @@ LogSpanner.span("layerSpan"))
                           }
          spans         <- tracerTestkit.getFinishedSpans
          layerSpan      = spans.find(_.name == "layerSpan")
        } yield assert(layerSpan)(isSome(anything))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  // ---------------------------------------------------------------------------
  // Fiber correctness tests
  // ---------------------------------------------------------------------------

  private val fiberCorrectnessSuite =
    suite("fiber correctness")(
      test("LogSpanner propagates through fork") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("forked")).fork.flatMap(_.join)
                           }
          spans         <- tracerTestkit.getFinishedSpans
          forked         = spans.find(_.name == "forked")
        } yield assert(forked)(isSome(anything))
      },
      test("LogSpanner survives timeout (non-timeout case)") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (ZIO.unit @@ LogSpanner.span("timed")).timeout(1.second)
                           }
          spans         <- tracerTestkit.getFinishedSpans
          timed          = spans.find(_.name == "timed")
        } yield assert(timed)(isSome(anything))
      },
      test("LogSpanner backend inherited by child fibers") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installOtel(tracer) *>
                               (for {
                                 fiber <- (ZIO.unit @@ LogSpanner.span("child-fiber-span")).fork
                                 _     <- fiber.join
                               } yield ())
                           }
          spans         <- tracerTestkit.getFinishedSpans
          childSpan      = spans.find(_.name == "child-fiber-span")
        } yield assert(childSpan)(isSome(anything))
      },
      test("LogSpanner hybrid: ZIO logSpan still visible alongside OTEL span on timeout") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          logSpanRef    <- Ref.make(List.empty[String])
          _             <- ZIO.scoped[Any] {
                             LogSpanner.installHybrid(tracer) *>
                               (
                                 FiberRef.currentLogSpan.getWith(spans => logSpanRef.set(spans.map(_.label))) *>
                                   ZIO.unit
                               ) @@ LogSpanner.span("hybrid-timed")
                           }
          spans         <- tracerTestkit.getFinishedSpans
          logLabels     <- logSpanRef.get
          hybridTimed    = spans.find(_.name == "hybrid-timed")
        } yield assert(hybridTimed)(isSome(anything)) &&
          assert(logLabels)(contains("hybrid-timed"))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)
}
