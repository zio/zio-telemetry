package zio.telemetry.opentelemetry.context

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{ContextPropagators, TextMapPropagator}

trait ContextPropagator {

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

object ContextPropagator {

  /**
   * An instance of W3C Trace Context Propagator.
   *
   * @see
   *   <a href="https://www.w3.org/TR/trace-context/">Trace Context</a>
   */
  val w3cTraceContext: ContextPropagator =
    new ContextPropagator {
      override val instance: TextMapPropagator =
        W3CTraceContextPropagator.getInstance()
    }

  /**
   * An instance of W3C Baggage Propagator.
   *
   * @see
   *   <a href="https://www.w3.org/TR/baggage/">Propagation format for distributed context: Baggage</a>
   */
  val w3cBaggage: ContextPropagator =
    new ContextPropagator {
      override val instance: TextMapPropagator =
        W3CBaggagePropagator.getInstance()
    }

  /**
   * Returns a propagator which does no injection or extraction.
   */
  val noop: ContextPropagator =
    new ContextPropagator {
      override val instance: TextMapPropagator =
        TextMapPropagator.noop
    }

  /**
   * Returns a propagator which simply delegates injection and extraction to the provided propagators.
   */
  def combined(propagators: ContextPropagator*): ContextPropagator =
    new ContextPropagator {
      override val instance: TextMapPropagator =
        TextMapPropagator.composite(propagators.map(_.instance): _*)
    }

  /**
   * An instance consists of both W3C Baggage and W3C Trace Context propagators.
   */
  val default: ContextPropagator =
    combined(w3cTraceContext, w3cBaggage)

  def fromJava(propagators: ContextPropagators): ContextPropagator =
    new ContextPropagator {
      override val instance: TextMapPropagator =
        propagators.getTextMapPropagator
    }

}
