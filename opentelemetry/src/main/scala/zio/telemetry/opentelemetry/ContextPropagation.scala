package zio.telemetry.opentelemetry

import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.{ UIO, URIO, ZIO }

private[opentelemetry] object ContextPropagation {

  /**
   * Extract and returns the context from carrier `C`.
   */
  def extractContext[C](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapPropagator.Getter[C]
  ): UIO[Context] =
    ZIO.uninterruptible {
      UIO(propagator.extract(Context.root(), carrier, getter))
    }

  /**
   * Injects the context into carrier `C`.
   */
  def injectContext[C](
    context: Context,
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapPropagator.Setter[C]
  ): URIO[Tracing, Unit] =
    UIO(propagator.inject(context, carrier, setter))

}
