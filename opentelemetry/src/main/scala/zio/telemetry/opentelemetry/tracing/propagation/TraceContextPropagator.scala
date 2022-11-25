package zio.telemetry.opentelemetry.tracing.propagation

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.telemetry.opentelemetry.internal.Propagator

trait TraceContextPropagator extends Propagator

object TraceContextPropagator {

  val default: TraceContextPropagator =
    new TraceContextPropagator {
      override val impl: TextMapPropagator =
        W3CTraceContextPropagator.getInstance()
    }

}
