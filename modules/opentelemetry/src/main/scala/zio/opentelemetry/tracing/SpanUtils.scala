package zio.opentelemetry.tracing

import io.opentelemetry.trace._
import zio.clock.Clock
import zio.{ Cause, UIO, URIO }

private[opentelemetry] object SpanUtils {

  /**
   * Determines whether the span is invalid.
   */
  def isInvalid(span: Span): Boolean =
    span.getContext.getSpanId == SpanId.getInvalid && span.getContext.getTraceId == TraceId.getInvalid

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
