package zio.opentelemetry

import io.opentelemetry.trace._
import zio.clock.Clock
import zio.{ Cause, UIO, URIO, ZIO }
import zio.opentelemetry.CurrentSpan.setCurrentSpan

object SpanUtils {

  /**
   * Determines whether the span is invalid.
   */
  def isInvalid(span: Span): Boolean =
    span.getContext.getSpanId == SpanId.getInvalid && span.getContext.getTraceId == TraceId.getInvalid

  /**
   * Ends the span `newSpan` according to the result of `effect`.
   * Reverts the current span to `oldSpan` after ending the span.
   */
  def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    oldSpan: Span,
    newSpan: Span
  ): ZIO[R with Clock with OpenTelemetry, E, A] =
    effect
      .tapCause(setErrorStatus(newSpan, _))
      .ensuring(endSpan(newSpan) *> (if (isInvalid(oldSpan)) UIO.unit else setCurrentSpan(oldSpan)))

  /**
   * Sets the status of `span` to `UNKNOWN` error with description being the pretty-printed cause.
   */
  def setErrorStatus(span: Span, cause: Cause[_]): UIO[Unit] =
    UIO(span.setStatus(Status.UNKNOWN.withDescription(cause.prettyPrint)))

  /**
   * Ends the span with the current time.
   */
  def endSpan(span: Span): URIO[Clock, Unit] = {
    def toEndTimestamp(time: Long): EndSpanOptions = EndSpanOptions.builder().setEndTimestamp(time).build()
    currentNanos.map(toEndTimestamp _ andThen span.end)
  }
}
