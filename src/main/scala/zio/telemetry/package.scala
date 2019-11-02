package zio

import io.opentracing.Span
import io.opentracing.propagation.Format
import zio.clock.Clock

package object telemetry {

  implicit class TelemetrySyntax[R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {

    def spanFrom[C <: Object](
      format: Format[C],
      carrier: C,
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with Clock with Telemetry, E, A] =
      Telemetry.spanFrom(
        format,
        carrier,
        zio,
        opName,
        tagError,
        logError
      )

    def root(
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with Clock with Telemetry, E, A] =
      Telemetry.root(zio, opName, tagError, logError)

    def span(
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with Clock with Telemetry, E, A] =
      Telemetry.span(zio, opName, tagError, logError)

    def span(
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R with Clock with Telemetry, E, A] =
      Telemetry.span(zio, span, tagError, logError)
  }
}
