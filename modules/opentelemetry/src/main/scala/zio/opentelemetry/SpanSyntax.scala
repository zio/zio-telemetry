package zio.opentelemetry

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat
import zio.clock.Clock
import io.opentelemetry.trace.Span
import zio.ZIO
import zio.opentelemetry.SpanUtils._
import zio.opentelemetry.OpenTelemetry._
import zio.opentelemetry.attributevalue.AttributeValueConverter
import zio.opentelemetry.CurrentSpan.{ getCurrentSpan, setCurrentSpan }

object SpanSyntax {

  implicit final class OpenTelemetryZioOps[R, E, A](val effect: ZIO[R, E, A]) extends AnyVal {

    /**
     * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span.
     * Ends the span when the effect finishes.
     */
    def spanFrom[C](
      httpTextFormat: HttpTextFormat,
      carrier: C,
      reader: PropagationFormat.Reader[C],
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Clock with OpenTelemetry, E, A] =
      for {
        old            <- getCurrentSpan
        extractedChild <- CurrentSpan.createChildFromExtracted(httpTextFormat, carrier, reader, spanName, spanKind)
        r              <- finalizeSpanUsingEffect(effect, old, extractedChild)
      } yield r

    /**
     * Sets the current span to be the new root span with name 'spanName'
     * Ends the span when the effect finishes.
     */
    def rootSpan(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Clock with OpenTelemetry, E, A] =
      for {
        old  <- getCurrentSpan
        root <- createRoot(spanName, spanKind)
        r    <- finalizeSpanUsingEffect(effect, old, root)
      } yield r

    /**
     * Sets the current span to be the child of the current span with name 'spanName'
     * Ends the span when the effect finishes.
     */
    def childSpan(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Clock with OpenTelemetry, E, A] =
      for {
        old   <- getCurrentSpan
        child <- createChild(spanName, spanKind)
        r     <- finalizeSpanUsingEffect(effect, old, child)
      } yield r

    /**
     * Sets the current span to `span`.
     * Ends the span when the effect finishes.
     */
    def span(span: Span): ZIO[R with Clock with OpenTelemetry, E, A] =
      for {
        old <- getCurrentSpan
        _   <- setCurrentSpan(span)
        r   <- finalizeSpanUsingEffect(effect, old, span)
      } yield r

    /**
     * Ends the current span when the effect finishes.
     */
    def endSpan: ZIO[R with Clock with OpenTelemetry, E, A] =
      for {
        current <- getCurrentSpan
        r       <- finalizeSpanUsingEffect(effect, current, current)
      } yield r

    /**
     * Adds an event to the current span
     */
    def addEvent(name: String): ZIO[OpenTelemetry with Clock with R, E, A] =
      effect <* CurrentSpan.addEvent(name)

    /**
     * Adds an event with attributes to the current span.
     */
    def addEventWithAttributes(
      name: String,
      attributes: Map[String, AttributeValue]
    ): ZIO[OpenTelemetry with Clock with R, E, A] =
      effect <* CurrentSpan.addEventWithAttributes(name, attributes)

    /**
     * Sets an attribute of the current span.
     */
    def setAttribute[V: AttributeValueConverter](name: String, value: V): ZIO[OpenTelemetry with Clock with R, E, A] =
      effect <* CurrentSpan.setAttribute(name, value)

  }
}
