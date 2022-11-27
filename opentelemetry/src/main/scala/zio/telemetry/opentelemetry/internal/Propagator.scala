package zio.telemetry.opentelemetry.internal

import io.opentelemetry.context.propagation.TextMapPropagator

private[opentelemetry] trait Propagator {

  /**
   * One of the already available or custom propagator implementations.
   *
   * @see
   *   <a
   *   href="https://opentelemetry.io/docs/reference/specification/context/api-propagators/#propagators-distribution">
   *   Propagators Distribution </a>
   */
  val instance: TextMapPropagator

}
