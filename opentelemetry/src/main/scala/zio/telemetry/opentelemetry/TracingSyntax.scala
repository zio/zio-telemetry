package zio.telemetry.opentelemetry

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator }
import io.opentelemetry.api.trace.{ Span, SpanKind, StatusCode }
import zio.ZIO

object TracingSyntax {

  implicit final class OpenTelemetryZioOps[-R, +E, +A](val effect: ZIO[R, E, A]) extends AnyVal {

    def spanFrom[C](
      propagator: TextMapPropagator,
      carrier: C,
      getter: TextMapGetter[C],
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing.Service, E, A] =
      Tracing.spanFrom(propagator, carrier, getter, spanName, spanKind, toErrorStatus)(effect)

    def root(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing.Service, E, A] = Tracing.root(spanName, spanKind, toErrorStatus)(effect)

    def span(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing.Service, E, A] = Tracing.span(spanName, spanKind, toErrorStatus)(effect)

    /**
     * Mark this effect as the child of an externally provided span. zio-opentelemetry will mark the span as being the
     * child of the external one.
     *
     * This is designed for use-cases where you are incrementally introducing zio & zio-telemetry in a project that
     * already makes use of instrumentation, and you need to interoperate with futures-based code.
     *
     * The caller is solely responsible for managing the external span, including calling Span.end
     */
    def inSpan(
      span: Span,
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: PartialFunction[E, StatusCode] = Map.empty
    ): ZIO[R with Tracing.Service, E, A] =
      Tracing.inSpan(span, spanName, spanKind, toErrorStatus)(effect)

    def addEvent(name: String): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.addEvent(name)

    def addEventWithAttributes(
      name: String,
      attributes: Attributes
    ): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.addEventWithAttributes(name, attributes)

    def setAttribute(name: String, value: Boolean): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: Double): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: Long): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute(name: String, value: String): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, value)

    def setAttribute[T](key: AttributeKey[T], value: T): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(key, value)

    def setAttribute(name: String, values: Seq[String]): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, values)

    def setAttribute(name: String, values: Seq[Boolean])(implicit
      i1: DummyImplicit
    ): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, values)

    def setAttribute(name: String, values: Seq[Long])(implicit
      i1: DummyImplicit,
      i2: DummyImplicit
    ): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, values)(i1, i2)

    def setAttribute(name: String, values: Seq[Double])(implicit
      i1: DummyImplicit,
      i2: DummyImplicit,
      i3: DummyImplicit
    ): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setAttribute(name, values)(i1, i2, i3)

    def setBaggage(name: String, value: String): ZIO[Tracing.Service with R, E, A] =
      effect <* Tracing.setBaggage(name, value)
  }
}
