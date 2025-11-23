package zio.telemetry.opentelemetry.extension.trace.propagation

import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.extension.trace.propagation.{B3Propagator, JaegerPropagator, OtTracePropagator}
import zio.telemetry.opentelemetry.core.context

/**
 * Implementations of [[io.opentelemetry.context.propagation.TextMapPropagator]] for various formats that are commonly
 * used in distributed tracing.
 *
 * To combine with the default OTEL propagators:
 *
 * {{{
 *   ContextPropagator.combine(
 *     ContextPropagator.default,
 *     zio.telemetry.opentelemetry.extension.trace.propagation.ContextPropagator.b3single,
 *     zio.telemetry.opentelemetry.extension.trace.propagation.ContextPropagator.b3multi,
 *     zio.telemetry.opentelemetry.extension.trace.propagation.ContextPropagator.jaeger,
 *     zio.telemetry.opentelemetry.extension.trace.propagation.ContextPropagator.opentracing
 *   )
 * }}}
 */
object ContextPropagator {

  /**
   * Implementation of the B3 propagation protocol.
   *
   * @see
   *   [[https://github.com/openzipkin/b3-propagation]]
   */
  val b3single: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        B3Propagator.injectingSingleHeader()
    }

  /**
   * Implementation of the B3 propagation protocol.
   *
   * @see
   *   [[https://github.com/openzipkin/b3-propagation]]
   */
  val b3multi: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        B3Propagator.injectingMultiHeaders()
    }

  /**
   * Implementation of the Jaeger propagation protocol.
   *
   * @see
   *   [[https://www.jaegertracing.io/docs/client-libraries/#propagation-format]]
   */
  val jaeger: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        JaegerPropagator.getInstance()
    }

  /**
   * Implementation of the protocol used by OpenTracing Basic Tracers.
   *
   * @see
   *   [[https://opentracing.io/specification/]]
   */
  val opentracing: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        OtTracePropagator.getInstance()
    }

}
