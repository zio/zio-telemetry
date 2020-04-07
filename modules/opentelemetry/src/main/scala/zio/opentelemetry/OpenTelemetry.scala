package zio.opentelemetry

import java.util.concurrent.TimeUnit

import io.opentelemetry.trace._
import zio.clock.Clock
import zio.{ FiberRef, UIO, URIO, URLayer, ZIO, ZLayer }
import zio.opentelemetry.SpanUtils.endSpan

object OpenTelemetry {
  trait Service {
    val currentSpan: FiberRef[Span]
    def createRoot(spanName: String, spanKind: Span.Kind): UIO[Span]
    def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): UIO[Span]
    def createChild(spanName: String, spanKind: Span.Kind): UIO[Span]
  }

  /**
   * Reference to the current span of the fiber.
   */
  def currentSpan: URIO[OpenTelemetry, FiberRef[Span]] =
    ZIO.access[OpenTelemetry](_.get.currentSpan)

  /**
   * Creates a new root span and sets it to be the current span.
   */
  def createRoot(spanName: String, spanKind: Span.Kind): URIO[OpenTelemetry, Span] =
    ZIO.accessM[OpenTelemetry](_.get.createRoot(spanName, spanKind))

  /**
   * Creates a new child span from the parent span, and sets it to be the current span.
   */
  def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): URIO[OpenTelemetry, Span] =
    ZIO.accessM[OpenTelemetry](_.get.createChildOf(spanName, parent, spanKind))

  /**
   * Creates a new child span from the current span, and sets it to be the current span.
   */
  def createChild(spanName: String, spanKind: Span.Kind): URIO[OpenTelemetry, Span] =
    ZIO.accessM[OpenTelemetry](_.get.createChild(spanName, spanKind))

  def live(tracer: Tracer): URLayer[Clock, OpenTelemetry] = {
    class Live(tracer: Tracer, rootSpan: FiberRef[Span], clock: Clock.Service) extends Service {
      def createRoot(spanName: String, spanKind: Span.Kind): UIO[Span] =
        for {
          nanoSeconds <- clock.currentTime(TimeUnit.NANOSECONDS)
          span <- UIO(
                   tracer
                     .spanBuilder(spanName)
                     .setNoParent()
                     .setSpanKind(spanKind)
                     .setStartTimestamp(nanoSeconds)
                     .startSpan()
                 )
          _ <- currentSpan.set(span)
        } yield span

      def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): UIO[Span] =
        for {
          span <- UIO(tracer.spanBuilder(spanName).setParent(parent).setSpanKind(spanKind).startSpan())
          _    <- currentSpan.set(span)
        } yield span

      def createChild(spanName: String, spanKind: Span.Kind): UIO[Span] =
        for {
          currentSpan <- currentSpan.get
          child       <- createChildOf(spanName, currentSpan, spanKind)
        } yield child

      val currentSpan: FiberRef[Span] = rootSpan
    }

    def createOpenTelemetry(tracer: Tracer): URIO[Clock, Service] =
      for {
        clock    <- ZIO.access[Clock](_.get)
        rootSpan <- FiberRef.make[Span](DefaultSpan.getInvalid)
      } yield new Live(tracer, rootSpan, clock)

    ZLayer.fromAcquireRelease(createOpenTelemetry(tracer))(_.currentSpan.get.flatMap(endSpan))
  }
}
