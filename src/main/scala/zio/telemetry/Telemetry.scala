package zio.telemetry

import java.util.concurrent.TimeUnit

import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import zio.Exit
import zio.FiberRef
import zio.IO
import zio.Task
import zio.UIO
import zio.ZIO
import zio.ZManaged
import zio.clock.Clock

import scala.jdk.CollectionConverters._

trait Telemetry extends Serializable {
  def telemetry: Telemetry.Service
}

object Telemetry {

  trait Service {
    def currentSpan: FiberRef[Span]
    def tracer: Tracer

    def underlying[R, R1 <: R with Telemetry, E, A](f: Tracer => ZIO[R, E, A]): ZIO[R1, E, A]

    def spanFrom[R, R1 <: R with Clock with Telemetry, E, A, C <: Object](
      format: Format[C],
      carrier: C,
      zio: ZIO[R, E, A],
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A]

    def root[R, R1 <: R with Clock with Telemetry, E, A](
      zio: ZIO[R, E, A],
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A]

    def span[R, R1 <: R with Clock with Telemetry, E, A](
      zio: ZIO[R, E, A],
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A]

    def span[R, R1 <: R with Clock with Telemetry, E, A](
      zio: ZIO[R, E, A],
      span: Span,
      tagError: Boolean,
      logError: Boolean
    ): ZIO[R1, E, A]

    def getBaggageItem(key: String): ZIO[Telemetry, Nothing, Option[String]]

    def setBaggageItem(
      key: String,
      value: String
    ): ZIO[Telemetry, Nothing, Unit]

    def tag(key: String, value: Int): ZIO[Telemetry, Nothing, Unit]
    def tag(key: String, value: String): ZIO[Telemetry, Nothing, Unit]
    def tag(key: String, value: Boolean): ZIO[Telemetry, Nothing, Unit]
    def tag[T <: Object](key: Tag[T], value: T): ZIO[Telemetry, Nothing, Unit]

    def log(msg: String): ZIO[Clock with Telemetry, Nothing, Unit]
    def log(fields: Map[String, _]): ZIO[Clock with Telemetry, Nothing, Unit]
  }

  def managed(tracer: Tracer, rootOpName: String = "ROOT"): ZManaged[Clock, Nothing, Telemetry.Service] =
    ZManaged.make(
      for {
        span    <- UIO(tracer.buildSpan(rootOpName).start())
        ref     <- FiberRef.make(span)
        tracer_ = tracer
      } yield new Telemetry.Service {
        override val currentSpan: FiberRef[Span] = ref
        override val tracer: Tracer              = tracer_
        override def underlying[R, R1 <: R with Telemetry, E, A](
          f: Tracer => ZIO[R, E, A]
        ): ZIO[R1, E, A] =
          getTracer.flatMap(f)

        override def spanFrom[R, R1 <: R with Clock with Telemetry, E, A, C <: Object](
          format: Format[C],
          carrier: C,
          zio: ZIO[R, E, A],
          opName: String,
          tagError: Boolean = true,
          logError: Boolean = true
        ): ZIO[R1, E, A] =
          getTracer.flatMap { tracer =>
            Task(tracer.extract(format, carrier))
              .fold(_ => None, Option.apply)
              .flatMap {
                case None => zio
                case Some(spanCtx) =>
                  span(
                    zio,
                    tracer.buildSpan(opName).asChildOf(spanCtx).start,
                    tagError,
                    logError
                  )
              }
          }
        override def root[R, R1 <: R with Clock with Telemetry, E, A](
          zio: ZIO[R, E, A],
          opName: String,
          tagError: Boolean = true,
          logError: Boolean = true
        ): ZIO[R1, E, A] =
          for {
            tracer <- getTracer
            root   <- UIO(tracer.buildSpan(opName).start())
            r      <- span(zio, root, tagError, logError)
          } yield r

        override def span[R, R1 <: R with Clock with Telemetry, E, A](
          zio: ZIO[R, E, A],
          opName: String,
          tagError: Boolean = true,
          logError: Boolean = true
        ): ZIO[R1, E, A] =
          for {
            tracer <- getTracer
            old    <- getSpan
            child  <- UIO(tracer.buildSpan(opName).asChildOf(old).start())
            r      <- span(zio, child, tagError, logError)
          } yield r

        override def span[R, R1 <: R with Clock with Telemetry, E, A](
          zio: ZIO[R, E, A],
          span: Span,
          tagError: Boolean,
          logError: Boolean
        ): ZIO[R1, E, A] =
          ZManaged
            .make[R1, E, Span](getSpan <* setSpan(span)) { old =>
              getCurrentTimeMicros.flatMap(now => UIO(span.finish(now))) *> setSpan(
                old
              )
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

        override def getBaggageItem(key: String): ZIO[Telemetry, Nothing, Option[String]] =
          getSpan.map(_.getBaggageItem(key)).map(Option.apply)

        override def setBaggageItem(
          key: String,
          value: String
        ): ZIO[Telemetry, Nothing, Unit] =
          getSpan.flatMap(span => UIO(span.setBaggageItem(key, value))).unit

        override def tag(key: String, value: String): ZIO[Telemetry, Nothing, Unit] =
          getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

        override def tag(key: String, value: Int): ZIO[Telemetry, Nothing, Unit] =
          getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

        override def tag(key: String, value: Boolean): ZIO[Telemetry, Nothing, Unit] =
          getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

        override def tag[T <: Object](key: Tag[T], value: T): ZIO[Telemetry, Nothing, Unit] =
          getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

        override def log(msg: String): ZIO[Clock with Telemetry, Nothing, Unit] =
          for {
            span <- getSpan
            now  <- getCurrentTimeMicros
            _    <- UIO(span.log(now, msg))
          } yield ()

        override def log(fields: Map[String, _]): ZIO[Clock with Telemetry, Nothing, Unit] =
          for {
            span <- getSpan
            now  <- getCurrentTimeMicros
            _    <- UIO(span.log(now, fields.asJava))
          } yield ()

      }
    )(_.currentSpan.get.flatMap(span => UIO(span.finish)))

  private def getSpan: ZIO[Telemetry, Nothing, Span] =
    ZIO.accessM[Telemetry](_.telemetry.currentSpan.get)

  private def setSpan(span: Span): ZIO[Telemetry, Nothing, Unit] =
    ZIO.accessM[Telemetry](_.telemetry.currentSpan.set(span))

  private def getTracer: ZIO[Telemetry, Nothing, Tracer] =
    ZIO.environment[Telemetry].map(_.telemetry.tracer)

  private def getCurrentTimeMicros: ZIO[Clock, Nothing, Long] =
    ZIO.accessM[Clock](_.clock.currentTime(TimeUnit.MICROSECONDS))

}
