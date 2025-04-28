package zio.telemetry.opentelemetry.baggage.propagation

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.telemetry.opentelemetry.context.internal.Propagator

trait BaggagePropagator extends Propagator

/**
 * Baggage Propagators.
 *
 * @see
 *   <a href="https://www.w3.org/TR/baggage/">Propagation format for distributed context: Baggage</a>
 */
object BaggagePropagator {

  /**
   * Instance of W3C Baggage Propagator.
   */
  val w3c: BaggagePropagator =
    new BaggagePropagator {
      override val instance: TextMapPropagator =
        W3CBaggagePropagator.getInstance()
    }

  /**
   * Instance of W3C Baggage Propagator.
   */
  val default: BaggagePropagator =
    w3c

}
