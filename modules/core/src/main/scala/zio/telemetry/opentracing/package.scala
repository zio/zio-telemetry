package zio.telemetry

import io.opentracing.propagation.Format
import io.opentracing.Span
import zio.clock.Clock
import zio._

package object opentracing {
  type OpenTracing = Has[OpenTracing.Service]

  implicit final class OpenTracingZioOps[R, E, A](val zio: ZIO[R, E, A]) extends AnyVal {

    def root[R1 <: R with Clock with OpenTracing](
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        telemetry <- getTelemetry
        root      <- telemetry.root(opName)
        r         <- span(telemetry)(root, tagError, logError)
      } yield r

    def span[R1 <: R with Clock with OpenTracing](
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        telemetry <- getTelemetry
        old       <- getSpan(telemetry)
        child     <- telemetry.span(old, opName)
        r         <- span(telemetry)(child, tagError, logError)
      } yield r

    def span[R1 <: R with Clock](telemetry: OpenTracing.Service)(
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R1, E, A] =
      for {
        old <- getSpan(telemetry)
        r <- (setSpan(telemetry)(span) *>
              zio.catchAllCause { cause =>
                telemetry.error(span, cause, tagError, logError) *>
                  IO.done(Exit.Failure(cause))
              }).ensuring(
              telemetry.finish(span) *>
                setSpan(telemetry)(old)
            )
      } yield r

    private def setSpan(telemetry: OpenTracing.Service)(span: Span): UIO[Unit] =
      telemetry.currentSpan.set(span)

    private def getSpan(telemetry: OpenTracing.Service): UIO[Span] =
      telemetry.currentSpan.get

    private def getTelemetry: ZIO[OpenTracing, Nothing, OpenTracing.Service] =
      ZIO.environment[OpenTracing].map(_.get[OpenTracing.Service])

    def spanFrom[R1 <: R with Clock with OpenTracing, C <: Object](
      format: Format[C],
      carrier: C,
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      OpenTracing.spanFrom(format, carrier, zio, opName, tagError, logError)

    def setBaggageItem(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      zio <* OpenTracing.setBaggageItem(key, value)

    def tag(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      zio <* OpenTracing.tag(key, value)

    def tag(key: String, value: Int): ZIO[R with OpenTracing, E, A] =
      zio <* OpenTracing.tag(key, value)

    def tag(key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
      zio <* OpenTracing.tag(key, value)

    def log(msg: String): ZIO[R with Clock with OpenTracing, E, A] =
      zio <* OpenTracing.log(msg)

    def log(fields: Map[String, _]): ZIO[R with Clock with OpenTracing, E, A] =
      zio <* OpenTracing.log(fields)

  }
}
