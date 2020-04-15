package zio.opentelemetry.tracing

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.Span
import zio.ZIO
import zio.clock.Clock
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverter

object TracingSyntax {

  implicit final class OpenTelemetryZioOps[R, E, A](val effect: ZIO[R, E, A]) extends AnyVal {

    /**
     * @see [[Tracing.spanFrom]]
     */
    def spanFrom[C](
      httpTextFormat: HttpTextFormat,
      carrier: C,
      getter: HttpTextFormat.Getter[C],
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Tracing, E, A] =
      Tracing.spanFrom(httpTextFormat, carrier, getter, spanName, spanKind)(effect)

    /**
     * @see [[Tracing.rootSpan]]
     */
    def rootSpan(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Tracing, E, A] = Tracing.rootSpan(spanName, spanKind)(effect)

    /**
     * @see [[Tracing.childSpan]]
     */
    def childSpan(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL
    ): ZIO[R with Tracing, E, A] = Tracing.childSpan(spanName, spanKind)(effect)

    /**
     * @see [[Tracing.addEvent]]
     */
    def addEvent(name: String): ZIO[Tracing with Clock with R, E, A] =
      effect <* Tracing.addEvent(name)

    /**
     * @see [[Tracing.addEventWithAttributes]]
     */
    def addEventWithAttributes(
      name: String,
      attributes: Map[String, AttributeValue]
    ): ZIO[Tracing with R, E, A] =
      effect <* Tracing.addEventWithAttributes(name, attributes)

    /**
     * @see [[Tracing.setAttribute]]
     */
    def setAttribute[V: AttributeValueConverter](name: String, value: V): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

  }
}
