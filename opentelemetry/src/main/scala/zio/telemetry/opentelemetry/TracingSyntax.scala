package zio.telemetry.opentelemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator }
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import zio.ZIO
import zio.clock.Clock

object TracingSyntax {

  implicit final class OpenTelemetryZioOps[R, E, A](val effect: ZIO[R, E, A]) extends AnyVal {

    def spanFrom[C](
      propagator: TextMapPropagator,
      carrier: C,
      getter: TextMapGetter[C],
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing, E, A] =
      Tracing.spanFrom(propagator, carrier, getter, spanName, spanKind, toErrorStatus)(effect)

    def root(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing, E, A] = Tracing.root(spanName, spanKind, toErrorStatus)(effect)

    def span(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing, E, A] = Tracing.span(spanName, spanKind, toErrorStatus)(effect)

    def addEvent(name: String): ZIO[Tracing with Clock with R, E, A] =
      effect <* Tracing.addEvent(name)

    def addEventWithAttributes(
      name: String,
      attributes: Attributes
    ): ZIO[Tracing with R, E, A] =
      effect <* Tracing.addEventWithAttributes(name, attributes)

    def setAttribute(name: String, value: Boolean): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: Double): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: Long): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: String): ZIO[Tracing with R, E, A] =
      effect <* Tracing.setAttribute(name, value)
  }
}
