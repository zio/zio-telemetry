package zio.telemetry

import io.opentracing.Span
import io.opentracing.propagation.Format
import zio.{ Exit, Has, IO, UIO, ZIO }

package object opentracing {
  type OpenTracing = Has[OpenTracing.Service]

  implicit final class OpenTracingZioOps[R, E, A](val zio: ZIO[R, E, A]) extends AnyVal {

    def root(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.root(zio, operation, tagError, logError)

    def span(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.span(zio, operation, tagError, logError)

    def span(service: OpenTracing.Service)(
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R, E, A] =
      for {
        old <- getSpan(service)
        r <- (setSpan(service)(span) *>
              zio.catchAllCause { cause =>
                service.error(span, cause, tagError, logError) *>
                  IO.done(Exit.Failure(cause))
              }).ensuring(
              service.finish(span) *>
                setSpan(service)(old)
            )
      } yield r

    private def setSpan(service: OpenTracing.Service)(span: Span): UIO[Unit] =
      service.currentSpan.set(span)

    private def getSpan(service: OpenTracing.Service): UIO[Span] =
      service.currentSpan.get

    def spanFrom[R1 <: R with OpenTracing, C <: Object](
      format: Format[C],
      carrier: C,
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      OpenTracing.spanFrom(format, carrier, zio, operation, tagError, logError)

    def setBaggageItem(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.setBaggageItem(zio, key, value)

    def tag(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def tag(key: String, value: Int): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def tag(key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def log(msg: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.log(zio, msg)

    def log(fields: Map[String, _]): ZIO[R with OpenTracing, E, A] =
      OpenTracing.log(zio, fields)

  }
}
