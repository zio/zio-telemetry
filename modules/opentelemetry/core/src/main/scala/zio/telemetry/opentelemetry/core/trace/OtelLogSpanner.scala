package zio.telemetry.opentelemetry.core.trace

import zio._

/**
 * OpenTelemetry-backed `LogSpanner` implementations.
 *
 * Provides factory methods for creating `LogSpanner` instances that create real OTEL spans, either exclusively or in
 * combination with ZIO's `logSpan`.
 *
 * @see
 *   [[LogSpanner]] for the dispatch mechanism
 */
object OtelLogSpanner {

  /**
   * Creates a `LogSpanner` that produces real OTEL spans via `Tracer.span`.
   *
   * Does NOT call `ZIO.logSpan` — span information is only visible through OTEL exporters.
   *
   * @param tracer
   *   the zio-telemetry Tracer to use for span creation
   * @return
   *   a LogSpanner that creates OTEL spans
   */
  def make(tracer: Tracer): LogSpanner = new LogSpanner {
    override def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A] =
      tracer.span(name)(_ => effect)
  }

  /**
   * Creates a `LogSpanner` that produces both OTEL spans AND ZIO logSpans.
   *
   * This gives dual visibility: OTEL spans for distributed tracing exporters, and ZIO logSpans for ZIO's built-in log
   * output (e.g., for local development).
   *
   * @param tracer
   *   the zio-telemetry Tracer to use for span creation
   * @return
   *   a LogSpanner that creates both OTEL and ZIO spans
   */
  def makeHybrid(tracer: Tracer): LogSpanner = new LogSpanner {
    override def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A] =
      tracer.span(name)(_ => ZIO.logSpan(name)(effect))
  }

  /**
   * A `ZLayer` that installs an OTEL-only `LogSpanner` for the current scope.
   *
   * Requires a `Tracer` in the environment. The installation is scoped — when the layer's scope closes, the previous
   * `LogSpanner` is restored.
   *
   * Usage:
   * {{{
   *   val program = myEffect @@ LogSpanner.span("op")
   *   program.provide(tracerLayer, OtelLogSpanner.layer)
   * }}}
   */
  val layer: ZLayer[Tracer, Nothing, Unit] =
    ZLayer.scoped(
      ZIO.serviceWithZIO[Tracer](tracer => LogSpanner.installLogSpanner(make(tracer)))
    )

  /**
   * A `ZLayer` that installs a hybrid `LogSpanner` (OTEL + ZIO logSpan) for the current scope.
   *
   * @see
   *   [[makeHybrid]] for dual-visibility behavior
   */
  val hybridLayer: ZLayer[Tracer, Nothing, Unit] =
    ZLayer.scoped(
      ZIO.serviceWithZIO[Tracer](tracer => LogSpanner.installLogSpanner(makeHybrid(tracer)))
    )
}
