package zio.telemetry.opentelemetry.internal

import io.opentelemetry.context.propagation.TextMapPropagator

private[opentelemetry] trait Propagator {

  val impl: TextMapPropagator

}
