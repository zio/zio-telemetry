package zio.telemetry.opentelemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.api.trace.{ Span, StatusCode }
import zio.ZIO
import zio.clock.Clock

object TracingSyntax {

  implicit final class OpenTelemetryZioOps[R, E, A](val effect: ZIO[R, E, A]) extends AnyVal {

    /**
     * @see [[Tracing.spanFrom]]
     */
    def spanFrom[C](
      propagator: TextMapPropagator,
      carrier: C,
      getter: TextMapPropagator.Getter[C],
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing, E, A] =
      Tracing.spanFrom(propagator, carrier, getter, spanName, spanKind, toErrorStatus)(effect)

    /**
     * @see [[Tracing.root]]
     */
    def root(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing, E, A] = Tracing.root(spanName, spanKind, toErrorStatus)(effect)

    /**
     * @see [[Tracing.span]]
     */
    def span(
      spanName: String,
      spanKind: Span.Kind = Span.Kind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
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
    def setAttribute(name: String, value: Boolean): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    /**
     * @see [[Tracing.setAttribute]]
     */
    def setAttribute(name: String, value: Double): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    /**
     * @see [[Tracing.setAttribute]]
     */
    def setAttribute(name: String, value: Long): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    /**
     * @see [[Tracing.setAttribute]]
     */
    def setAttribute(name: String, value: String): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

  }
}
