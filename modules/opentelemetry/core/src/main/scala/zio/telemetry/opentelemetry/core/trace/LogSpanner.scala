package zio.telemetry.opentelemetry.core.trace

import zio._

/**
 * A pluggable span dispatch mechanism that bridges ZIO's `logSpan` with OpenTelemetry spans.
 *
 * By default, `LogSpanner.span("name")` delegates to `ZIO.logSpan`, adding zero OTEL overhead. When an OTEL-backed
 * `LogSpanner` is installed (via `LogSpanner.installLogSpanner`), the same call creates real OTEL spans instead.
 *
 * This allows library code to use `LogSpanner.span` without depending on `Tracer`, while application code installs the
 * OTEL backend at the edge.
 *
 * Implements the design proposed in zio-telemetry #1022.
 *
 * @see
 *   [[OtelLogSpanner]] for OTEL-backed implementations
 */
trait LogSpanner {

  /**
   * Wraps an effect with a named span.
   *
   * The semantics depend on the installed backend:
   *   - Default: delegates to `ZIO.logSpan` (ZIO log-based spans only)
   *   - OTEL: creates a real OpenTelemetry span via `Tracer.span`
   *   - Hybrid: creates both an OTEL span and a ZIO logSpan
   *
   * @param name
   *   the span name
   * @param effect
   *   the effect to wrap
   */
  def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A]
}

object LogSpanner {

  /**
   * Default implementation that delegates to `ZIO.logSpan`. Zero OTEL overhead — no spans are exported.
   */
  val default: LogSpanner = new LogSpanner {
    override def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.logSpan(name)(effect)
  }

  /**
   * Global FiberRef storing the current LogSpanner.
   *
   * Uses the same `Unsafe.unsafe { FiberRef.unsafe.make }` pattern as `FiberRef.currentLogSpan` in ZIO core. This
   * ensures the FiberRef is available at module initialization time without requiring a ZIO runtime.
   */
  private[opentelemetry] val currentLogSpanner: FiberRef[LogSpanner] =
    Unsafe.unsafe { implicit unsafe =>
      FiberRef.unsafe.make[LogSpanner](LogSpanner.default)
    }

  /**
   * Installs a custom `LogSpanner` for the current scope.
   *
   * When the scope closes, the previous `LogSpanner` is automatically restored. This is the primary mechanism for
   * activating OTEL-backed span dispatch.
   *
   * @param logSpanner
   *   the LogSpanner implementation to install
   * @return
   *   a scoped effect that reverts the installation on scope close
   */
  def installLogSpanner(logSpanner: LogSpanner): ZIO[Scope, Nothing, Unit] =
    currentLogSpanner.locallyScoped(logSpanner)

  /**
   * A `ZIOAspect` that wraps an effect with a named span using the currently installed `LogSpanner`.
   *
   * Usage:
   * {{{
   *   myEffect @@ LogSpanner.span("operationName")
   * }}}
   *
   * @param spanName
   *   the span name
   * @return
   *   a ZIOAspect that applies the span
   */
  def span(spanName: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        currentLogSpanner.getWith(_.logSpan(spanName, effect))
    }
}
