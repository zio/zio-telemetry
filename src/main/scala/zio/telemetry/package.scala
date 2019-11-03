package zio

import zio.clock.Clock

package object telemetry {

  implicit class TelemetryOps[S, R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {

    def root[R1 <: R with Clock with Telemetry[S]](
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        telemetry <- getTelemetry
        root      <- telemetry.root(opName)
        r         <- span(root, tagError, logError)
      } yield r

    def span[R1 <: R with Clock with Telemetry[S]](
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        telemetry <- getTelemetry
        old       <- getSpan
        child     <- telemetry.span(old, opName)
        r         <- span(child, tagError, logError)
      } yield r

    def span[R1 <: R with Clock with Telemetry[S]](
      span: S,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R1, E, A] =
      ZManaged
        .make[R1, E, S](getSpan <* setSpan(span)) { old =>
          getTelemetry.flatMap(_.finish(span)) *> setSpan(old)
        }
        .use(
          _ =>
            zio.catchAllCause { cause =>
              tag("error", true).when(tagError) *>
                log(
                  Map("error.object" -> cause, "stack" -> cause.prettyPrint)
                ).when(logError) *>
                IO.done(Exit.Failure(cause))
            }
        )

    private def setSpan(span: S): ZIO[Telemetry[S], Nothing, Unit] =
      ZIO.accessM[Telemetry[S]](_.telemetry.currentSpan.set(span))

    private def getSpan: ZIO[Telemetry[S], Nothing, S] =
      ZIO.accessM[Telemetry[S]](_.telemetry.currentSpan.get)

    private def getTelemetry: ZIO[Telemetry[S], Nothing, Telemetry.Service[S]] =
      ZIO.environment[Telemetry[S]].map(_.telemetry)
  }
}
