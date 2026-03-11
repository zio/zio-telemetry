package zio.telemetry.opentelemetry.core.trace

import zio._

/**
 * A pluggable span dispatch mechanism that bridges ZIO's `logSpan` with OpenTelemetry spans.
 *
 * By default, `LogSpanner.span("name")` delegates to `ZIO.logSpan`, adding zero OTEL overhead. When an OTEL-backed
 * `LogSpanner` is installed (via `LogSpanner.installOtel` or `LogSpanner.installHybrid`), the same call creates real
 * OTEL spans instead.
 *
 * This allows library code to use `LogSpanner.span` without depending on `Tracer`, while application code installs the
 * OTEL backend at the edge.
 *
 * Implements the design proposed in zio-telemetry #1022.
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
   * @param zio
   *   the effect to wrap
   */
  def logSpan[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
}

object LogSpanner {

  /**
   * Default implementation that delegates to `ZIO.logSpan`. Zero OTEL overhead — no spans are exported.
   */
  private[opentelemetry] val default: LogSpanner = new LogSpanner {

    override def logSpan[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ZIO.logSpan(name)(zio)
  }

  /**
   * Global FiberRef storing the current LogSpanner.
   *
   * Uses the same `Unsafe.unsafe { FiberRef.unsafe.make }` pattern as `FiberRef.currentLogSpan` in ZIO core. This
   * ensures the FiberRef is available at module initialization time without requiring a ZIO runtime.
   *
   * IMPORTANT: `default` must be defined before this val to avoid null initialization.
   */
  private[opentelemetry] val currentLogSpanner: FiberRef[LogSpanner] =
    Unsafe.unsafe { implicit unsafe =>
      FiberRef.unsafe.make[LogSpanner](LogSpanner.default)
    }

  /**
   * Installs a `LogSpanner` that produces real OTEL spans via `Tracer.span`.
   *
   * Does NOT call `ZIO.logSpan` — span information is only visible through OTEL exporters.
   *
   * @param tracer
   *   the zio-telemetry Tracer to use for span creation
   * @return
   *   a scoped effect that installs the OTEL backend and reverts on scope close
   */
  def installOtel(tracer: Tracer)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] = {
    val logSpanner = new LogSpanner {
      override def logSpan[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        tracer.span(name)(_ => zio)
    }

    currentLogSpanner.locallyScoped(logSpanner)
  }

  /**
   * Installs a `LogSpanner` that produces both OTEL spans AND ZIO logSpans.
   *
   * This gives dual visibility: OTEL spans for distributed tracing exporters, and ZIO logSpans for ZIO's built-in log
   * output (e.g., for local development).
   *
   * @param tracer
   *   the zio-telemetry Tracer to use for span creation
   * @return
   *   a scoped effect that installs the hybrid backend and reverts on scope close
   */
  def installHybrid(tracer: Tracer)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] = {
    val logSpanner = new LogSpanner {
      override def logSpan[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        tracer.span(name)(_ => ZIO.logSpan(name)(zio))
    }

    currentLogSpanner.locallyScoped(logSpanner)
  }

  /**
   * A `ZIOAspect` that wraps an effect with a named span using the currently installed `LogSpanner`.
   *
   * This is the primary call-site API for library code that should not depend on `Tracer` or `OpenTelemetry`.
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
      override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        currentLogSpanner.getWith(_.logSpan(spanName)(zio))
    }
}
