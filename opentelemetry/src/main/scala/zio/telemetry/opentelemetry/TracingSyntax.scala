package zio.telemetry.opentelemetry

import io.opentelemetry.common.Attributes
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.{ Span, Status }
import zio.ZIO
import zio.clock.Clock
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverter

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
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, Status] = Map.empty
    ): ZIO[R with Tracing, E, A] =
      Tracing.spanFrom(httpTextFormat, carrier, getter, spanName, spanKind, toErrorStatus)(effect)

    /**
     * @see [[Tracing.root]]
     */
    def root(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, Status] = Map.empty
    ): ZIO[R with Tracing, E, A] = Tracing.root(spanName, spanKind, toErrorStatus)(effect)

    /**
     * @see [[Tracing.span]]
     */
    def span(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, Status] = Map.empty
    ): ZIO[R with Tracing, E, A] = Tracing.span(spanName, spanKind, toErrorStatus)(effect)

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
      attributes: Attributes
    ): ZIO[Tracing with R, E, A] =
      effect <* Tracing.addEventWithAttributes(name, attributes)

    /**
     * @see [[Tracing.setAttribute]]
     */
    def setAttribute[V: AttributeValueConverter](name: String, value: V): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

  }
}
