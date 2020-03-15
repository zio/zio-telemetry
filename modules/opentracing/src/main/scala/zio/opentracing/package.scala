package zio

import io.opentracing.Span
import io.opentracing.propagation.Format
import zio.clock.Clock

package object opentracing {
  type OpenTracing = Has[OpenTracing.Service]

  implicit final class OpenTracingZioOps[R, E, A](val zio: ZIO[R, E, A]) extends AnyVal {

    def root[R1 <: R with Clock with OpenTracing](
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        service <- getService
        root    <- service.root(operation)
        r       <- span(service)(root, tagError, logError)
      } yield r

    def span[R1 <: R with OpenTracing](
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      for {
        service <- getService
        old     <- getSpan(service)
        child   <- service.span(old, operation)
        r       <- span(service)(child, tagError, logError)
      } yield r

    def span[R1 <: R](service: OpenTracing.Service)(
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R1, E, A] =
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

    private def getService: URIO[OpenTracing, OpenTracing.Service] =
      ZIO.access[OpenTracing](_.get)

    def spanFrom[C <: AnyRef](
      format: Format[C],
      carrier: C,
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(format, carrier, zio, operation, tagError, logError)

    def spanFrom[C <: AnyRef](
      format: Format[C],
      carrier: C,
      operation: String
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(format, carrier, zio, operation, tagError = true, logError = true)

    def spanFrom(
      headers: Map[String, String],
      operation: String,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(headers, zio, operation, tagError, logError)

    def spanFrom(
      headers: Map[String, String],
      operation: String
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(headers, zio, operation, tagError = true, logError = true)

    def spanFrom(
      context: String,
      operation: String,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(context, zio, operation, tagError, logError)

    def spanFrom(
      context: String,
      operation: String
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.spanFrom(context, zio, operation, tagError = true, logError = true)

    def inject[C <: AnyRef](format: Format[C], carrier: C): URIO[OpenTracing, Unit] =
      OpenTracing.inject(format, carrier)

    def inject: URIO[OpenTracing, Map[String, String]] =
      OpenTracing.inject

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
