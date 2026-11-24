package zio.telemetry.opentelemetry.baggage.propagation

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.telemetry.opentelemetry.internal.Propagator

/**
 * Propagates baggage between processes. Pass it to the [[zio.telemetry.opentelemetry.baggage.Baggage]] methods that
 * extract or inject baggage.
 */
trait BaggagePropagator extends Propagator

/**
 * Constructors for [[BaggagePropagator]].
 */
object BaggagePropagator {

  /**
   * Instance of W3C Baggage Propagator.
   *
   * @see
   *   <a href="https://www.w3.org/TR/baggage/">Propagation format for distributed context: Baggage</a>
   */
  val default: BaggagePropagator =
    new BaggagePropagator {
      override val instance: TextMapPropagator =
        W3CBaggagePropagator.getInstance()
    }

}
