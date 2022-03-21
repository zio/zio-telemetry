package zio.telemetry.opentelemetry

import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio.{ UIO, URIO, ZIO }

private[opentelemetry] object ContextPropagation {

  /**
   * Extract and returns the context from carrier `C`.
   */
  def extractContext[C](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C]
  ): UIO[Context] =
    ZIO.uninterruptible {
      ZIO.succeed(propagator.extract(Context.root(), carrier, getter))
    }

  /**
   * Injects the context into carrier `C`.
   */
  def injectContext[C](
    context: Context,
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapSetter[C]
  ): URIO[Tracing.Service, Unit] =
    ZIO.succeed(propagator.inject(context, carrier, setter))

}
