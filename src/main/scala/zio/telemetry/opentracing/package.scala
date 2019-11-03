package zio.telemetry

import java.util.concurrent.TimeUnit

import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import zio.Cause
import zio.FiberRef
import zio.Task
import zio.UIO
import zio.URIO
import zio.ZIO
import zio.ZManaged
import zio.clock.Clock

import scala.jdk.CollectionConverters._

package object opentracing {

  def managed(tracer: Tracer, rootOpName: String = "ROOT"): ZManaged[Clock, Nothing, OpenTracing] =
    ZManaged.make(
      for {
        span    <- UIO(tracer.buildSpan(rootOpName).start())
        ref     <- FiberRef.make(span)
        tracer_ = tracer
      } yield new OpenTracing {

        override val telemetry: OpenTracing.Service = new OpenTracing.Service {
          override type A = Span

          override val tracer: Tracer              = tracer_
          override val currentSpan: FiberRef[Span] = ref

          override def root(opName: String): URIO[Clock, Span] =
            UIO(tracer.buildSpan(opName).start())

          override def span(span: Span, opName: String): URIO[Clock, Span] =
            for {
              old   <- currentSpan.get
              child <- UIO(tracer.buildSpan(opName).asChildOf(old).start())
            } yield child

          override def finish(span: Span): URIO[Clock, Unit] =
            URIO.accessM(_.clock.currentTime(TimeUnit.MICROSECONDS).map(span.finish))

          override def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
            UIO(span.setTag("error", true)).when(tagError) *>
              UIO(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)

        }
      }
    )(_.telemetry.currentSpan.get.flatMap(span => UIO(span.finish())))

  implicit final class OpenTracingOps(val telemetry: OpenTracing.Service) extends AnyVal {
    def spanFrom[R, R1 <: R with Clock, E, A, C <: Object](
      format: Format[C],
      carrier: C,
      zio: ZIO[R, E, A],
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      Task(telemetry.tracer.extract(format, carrier))
        .fold(_ => None, Option.apply)
        .flatMap {
          case None => zio
          case Some(spanCtx) =>
            zio.span(telemetry)(
              telemetry.tracer.buildSpan(opName).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }

    def inject[C <: Object](format: Format[C], carrier: C): ZIO[Any, Nothing, Unit] =
      telemetry.currentSpan.get.flatMap { span =>
        ZIO.effectTotal(telemetry.tracer.inject(span.context(), format, carrier)).unit
      }

    def context: UIO[SpanContext] =
      telemetry.currentSpan.get.map(_.context)

    def getBaggageItem(key: String): UIO[Option[String]] =
      getSpan.map(_.getBaggageItem(key)).map(Option(_))

    def setBaggageItem(key: String, value: String): UIO[Unit] =
      getSpan.map(_.setBaggageItem(key, value)).unit

    def tag(key: String, value: String): UIO[Unit] =
      getSpan.map(_.setTag(key, value)).unit

    def tag(key: String, value: Int): UIO[Unit] =
      getSpan.map(_.setTag(key, value)).unit

    def tag(key: String, value: Boolean): UIO[Unit] =
      getSpan.map(_.setTag(key, value)).unit

    def log(msg: String): ZIO[Clock, Nothing, Unit] =
      for {
        span <- getSpan
        now  <- getCurrentTimeMicros
        _    <- UIO(span.log(now, msg))
      } yield ()

    def log(fields: Map[String, _]): ZIO[Clock, Nothing, Unit] =
      for {
        span <- getSpan
        now  <- getCurrentTimeMicros
        _    <- UIO(span.log(now, fields.asJava))
      } yield ()

    private def getSpan: UIO[Span] =
      telemetry.currentSpan.get

    private def getCurrentTimeMicros: ZIO[Clock, Nothing, Long] =
      ZIO.accessM(_.clock.currentTime(TimeUnit.MICROSECONDS))

  }

  implicit final class OpenTracingZioOps[R, E, A](val zio: ZIO[R, E, A]) extends AnyVal {

    def spanFrom[R1 <: R with Clock with OpenTracing.Service, C <: Object](
      format: Format[C],
      carrier: C,
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      ZIO.accessM { _.telemetry.spanFrom(format, carrier, zio, opName, tagError, logError) }

    def setBaggageItem(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.setBaggageItem(key, value) }

    def tag(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.tag(key, value) }

    def tag(key: String, value: Int): ZIO[R with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.tag(key, value) }

    def tag(key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.tag(key, value) }

    def log(msg: String): ZIO[R with Clock with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.log(msg) }

    def log(fields: Map[String, _]): ZIO[R with Clock with OpenTracing, E, A] =
      zio <* ZIO.accessM { _.telemetry.log(fields) }

  }
}
