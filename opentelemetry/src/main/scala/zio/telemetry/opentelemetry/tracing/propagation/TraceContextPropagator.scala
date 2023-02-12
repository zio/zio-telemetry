package zio.telemetry.opentelemetry.tracing.propagation

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.telemetry.opentelemetry.internal.Propagator

trait TraceContextPropagator extends Propagator

object TraceContextPropagator {

  /**
   * Instance of W3C Trace Context Propagator.
   *
   * @see
   *   <a href="https://www.w3.org/TR/trace-context/">Trace Context</a>
   */
  val default: TraceContextPropagator =
    new TraceContextPropagator {
      override val instance: TextMapPropagator =
        W3CTraceContextPropagator.getInstance()
    }

}
