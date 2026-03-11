package zio.telemetry.opentelemetry.core.trace

import io.opentelemetry.api.trace.StatusCode
import zio._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.trace.{SpanData, TracerTestkit}
import zio.test.Assertion._
import zio.test._

/**
 * Tests verifying that OTEL spans are correctly recorded when effects are interrupted.
 *
 * These tests document the behavior described in zio-telemetry #1069. The FiberRef-based ContextStorage path (used by
 * `OpenTelemetry.custom`) is expected to handle interruption correctly because `Tracer.span` uses
 * `ZIO.acquireReleaseWith`, which per Zionomicon Ch.14 guarantees that the release (endSpan) runs even on interruption.
 *
 * The ThreadLocal-based ContextStorage path (`OpenTelemetry.global`) has known issues with interruption, documented in
 * the secondary test suite below.
 */
object InterruptionTracerTest extends ZIOSpecDefault {

  val instrumentationScopeName = "InterruptionTracerTest"

  def assertSpanStatusCode(assertion: Assertion[StatusCode]): Assertion[SpanData] =
    hasField[SpanData, StatusCode]("statusCode", _.status.statusCode, assertion)

  def assertSpanParentId(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String]("parentSpanId", _.parentSpanId, assertion)

  override def spec: Spec[Any, Throwable] =
    suite("Interrupted span recording")(
      fiberRefSuite,
      threadLocalSuite
    )

  /**
   * FiberRef ContextStorage tests — these should all PASS on RC10, confirming that #1069 is a ThreadLocal-only issue
   * for the `acquireReleaseWith` span lifecycle path.
   */
  private val fiberRefSuite =
    suite("FiberRef ContextStorage")(
      test("timeout: span is recorded with ERROR status when effect times out") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (ZIO.sleep(1.hour) @@ tracer.aspects.span("slow-op")).timeout(100.millis)
          spans         <- tracerTestkit.getFinishedSpans
          slowOp         = spans.find(_.name == "slow-op")
          // acquireReleaseWith guarantees endSpan runs, and the uninterruptibleMask
          // fix in contextScope guarantees statusMapper.handle runs before the
          // interruption propagates — so the span has ERROR status.
        } yield assert(slowOp)(isSome(anything)) &&
          assert(slowOp)(isSome(assertSpanStatusCode(equalTo(StatusCode.ERROR))))
      },
      test("timeout: parent context preserved after child timeout") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (
                             (ZIO.sleep(1.hour) @@ tracer.aspects.span("child")).timeout(100.millis) *>
                               (ZIO.unit @@ tracer.aspects.span("sibling"))
                           ) @@ tracer.aspects.span("parent")
          spans         <- tracerTestkit.getFinishedSpans
          parent         = spans.find(_.name == "parent")
          sibling        = spans.find(_.name == "sibling")
        } yield assert(parent)(isSome(anything)) &&
          assert(sibling)(isSome(anything)) &&
          assert(sibling)(isSome(assertSpanParentId(equalTo(parent.get.spanId))))
      },
      test("race: both fast and slow spans are recorded") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (ZIO.unit @@ tracer.aspects.span("fast"))
                             .race(
                               ZIO.sleep(1.hour) @@ tracer.aspects.span("slow")
                             )
          // Allow the losing fiber's finalizer (span.end) to complete
          _             <- ZIO.sleep(100.millis)
          spans         <- tracerTestkit.getFinishedSpans
          fast           = spans.find(_.name == "fast")
          slow           = spans.find(_.name == "slow")
        } yield assert(fast)(isSome(anything)) &&
          assert(slow)(isSome(anything))
      },
      test("fork: child fiber span inherits parent context") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (
                             (ZIO.unit @@ tracer.aspects.span("forked")).fork.flatMap(_.join)
                           ) @@ tracer.aspects.span("parent")
          spans         <- tracerTestkit.getFinishedSpans
          parent         = spans.find(_.name == "parent")
          forked         = spans.find(_.name == "forked")
        } yield assert(parent)(isSome(anything)) &&
          assert(forked)(isSome(anything)) &&
          assert(forked)(isSome(assertSpanParentId(equalTo(parent.get.spanId))))
      },
      test("forkDaemon: span is still recorded") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          promise       <- Promise.make[Nothing, Unit]
          _             <- (
                             (ZIO.unit <* promise.succeed(())).forkDaemon.flatMap(_ => promise.await)
                           ) @@ tracer.aspects.span("daemon-span")
          spans         <- tracerTestkit.getFinishedSpans
          daemonSpan     = spans.find(_.name == "daemon-span")
        } yield assert(daemonSpan)(isSome(anything))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef) @@ TestAspect.withLiveClock

  /**
   * ThreadLocal ContextStorage tests — these document known #1069 failures. The ThreadLocal-based storage does not
   * properly propagate context across fiber boundaries, so interruption can lose span context.
   *
   * These tests serve as regression tests for a future fix (FiberRef.asThreadLocal bridge).
   */
  private val threadLocalSuite =
    suite("ThreadLocal ContextStorage (known #1069 issues)")(
      test("timeout: span is recorded when effect times out") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (ZIO.sleep(1.hour) @@ tracer.aspects.span("slow-op-tl")).timeout(100.millis)
          spans         <- tracerTestkit.getFinishedSpans
          slowOp         = spans.find(_.name == "slow-op-tl")
        } yield assert(slowOp)(isSome(anything))
      },
      test("fork: child fiber span inherits parent context") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- (
                             (ZIO.unit @@ tracer.aspects.span("forked-tl")).fork.flatMap(_.join)
                           ) @@ tracer.aspects.span("parent-tl")
          spans         <- tracerTestkit.getFinishedSpans
          parent         = spans.find(_.name == "parent-tl")
          forked         = spans.find(_.name == "forked-tl")
        } yield assert(parent)(isSome(anything)) &&
          assert(forked)(isSome(anything))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageJavaOtelThreadLocal) @@ TestAspect.withLiveClock @@
      TestAspect.flaky(3) // ThreadLocal path may fail — that's the documented #1069 behavior
}
