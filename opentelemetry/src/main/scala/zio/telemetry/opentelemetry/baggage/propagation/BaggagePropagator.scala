package zio.telemetry.opentelemetry.baggage.propagation

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.telemetry.opentelemetry.internal.Propagator

trait BaggagePropagator extends Propagator

object BaggagePropagator {

  val default: BaggagePropagator =
    new BaggagePropagator {
      override val impl: TextMapPropagator =
        W3CBaggagePropagator.getInstance()
    }

}
