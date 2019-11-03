package zio

import zio.clock.Clock

package object telemetry {

  implicit class TelemetryOps[R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {

    def root[R1 <: R with Clock with Telemetry](
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        telemetry <- getTelemetry
        root      <- telemetry.root(opName)
        r         <- span(telemetry)(root, tagError, logError)
      } yield r

    def span[R1 <: R with Clock with Telemetry](
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

    def span[R1 <: R with Clock](telemetry: Telemetry.Service)(
      span: telemetry.A,
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

    private def setSpan(telemetry: Telemetry.Service)(span: telemetry.A): UIO[Unit] =
      telemetry.currentSpan.set(span)

    private def getSpan(telemetry: Telemetry.Service): UIO[telemetry.A] =
      telemetry.currentSpan.get

    private def getTelemetry: ZIO[Telemetry, Nothing, Telemetry.Service] =
      ZIO.environment[Telemetry].map(_.telemetry)
  }
}
