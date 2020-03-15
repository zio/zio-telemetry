package zio.opentracing

import io.opentracing.propagation.Format
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import zio._
import zio.clock.Clock

object OpenTracing {

  trait Service {
    private[opentracing] val tracer: Tracer
    def currentSpan: FiberRef[Span]
    def root(opName: String): UIO[Span]
    def span(span: Span, opName: String): UIO[Span]
    def finish(span: Span): UIO[Unit]
    def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit]
  }

  def live(tracer: Tracer, rootOpName: String = "ROOT"): URLayer[Clock, OpenTracing] =
    ZLayer.fromManaged(managed(tracer, rootOpName))

  private[opentracing] def managed(tracer0: Tracer, rootOpName: String): URManaged[Clock, OpenTracing.Service] =
    ZManaged.make(
      for {
        span  <- UIO(tracer0.buildSpan(rootOpName).start())
        ref   <- FiberRef.make(span)
        clock <- ZIO.access[Clock](_.get)
      } yield new OpenTracing.Service {
        override val tracer: Tracer              = tracer0
        override val currentSpan: FiberRef[Span] = ref

        override def root(opName: String): UIO[Span] =
          UIO(tracer.buildSpan(opName).start())

        override def span(span: Span, opName: String): UIO[Span] =
          for {
            old   <- currentSpan.get
            child <- UIO(tracer.buildSpan(opName).asChildOf(old).start())
          } yield child

        override def finish(span: Span): UIO[Unit] =
          clock.currentTime(TimeUnit.MICROSECONDS).map(span.finish)

        override def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
          UIO(span.setTag("error", true)).when(tagError) *>
            UIO(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)
      }
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

  def spanFrom[R, R1 <: R with Clock with OpenTracing, E, Span, C <: Object](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, Span],
    opName: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R1, E, Span] =
    ZIO.accessM { env =>
      val service = env.get[OpenTracing.Service]
      Task(service.tracer.extract(format, carrier))
        .fold(_ => None, Option.apply)
        .flatMap {
          case None => zio
          case Some(spanCtx) =>
            zio.span(service)(
              service.tracer.buildSpan(opName).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }
    }

  def inject[C <: Object](format: Format[C], carrier: C): ZIO[OpenTracing, Nothing, Unit] =
    ZIO.accessM { env =>
      val service = env.get[OpenTracing.Service]
      service.currentSpan.get.flatMap { span =>
        ZIO.effectTotal(service.tracer.inject(span.context(), format, carrier)).unit
      }
    }

  def context: ZIO[OpenTracing, Nothing, SpanContext] =
    ZIO.accessM(_.get.currentSpan.get.map(_.context))

  def getBaggageItem(key: String): URIO[OpenTracing, Option[String]] =
    getSpan.map(_.getBaggageItem(key)).map(Option(_))

  def setBaggageItem(key: String, value: String): URIO[OpenTracing, Unit] =
    getSpan.map(_.setBaggageItem(key, value)).unit

  def tag(key: String, value: String): URIO[OpenTracing, Unit] =
    getSpan.map(_.setTag(key, value)).unit

  def tag(key: String, value: Int): URIO[OpenTracing, Unit] =
    getSpan.map(_.setTag(key, value)).unit

  def tag(key: String, value: Boolean): URIO[OpenTracing, Unit] =
    getSpan.map(_.setTag(key, value)).unit

  def log(msg: String): ZIO[Clock with OpenTracing, Nothing, Unit] =
    for {
      span <- getSpan
      now  <- getCurrentTimeMicros
      _    <- UIO(span.log(now, msg))
    } yield ()

  def log(fields: Map[String, _]): ZIO[Clock with OpenTracing, Nothing, Unit] =
    for {
      span <- getSpan
      now  <- getCurrentTimeMicros
      _    <- UIO(span.log(now, fields.asJava))
    } yield ()

  private def getSpan: URIO[OpenTracing, Span] =
    ZIO.accessM(_.get.currentSpan.get)

  private def getCurrentTimeMicros: ZIO[Clock, Nothing, Long] =
    ZIO.accessM(_.get.currentTime(TimeUnit.MICROSECONDS))
}
