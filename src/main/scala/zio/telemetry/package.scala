package zio

import io.opentracing.Span
import zio.clock.Clock

package object telemetry {

  implicit class TelemetryOps[R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {
    def root(
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with Clock with Telemetry, E, A] =
      ZIO.accessM(_.telemetry.root(zio, opName, tagError, logError))

    def span(
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with Clock with Telemetry, E, A] =
      ZIO.accessM(_.telemetry.span(zio, opName, tagError, logError))

    def span(
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R with Clock with Telemetry, E, A] =
      ZIO.accessM(_.telemetry.span(zio, span, tagError, logError))
  }
}
