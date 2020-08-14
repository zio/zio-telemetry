package zio.telemetry.opentracing

import java.util.concurrent.TimeUnit

import io.opentracing.propagation.Format
import io.opentracing.{ Span, SpanContext, Tracer }
import io.opentracing.noop.NoopTracerFactory
import zio._
import zio.clock.Clock

import scala.jdk.CollectionConverters._

object OpenTracing {

  trait Service {
    private[opentracing] val tracer: Tracer

    def currentSpan: FiberRef[Span]
    def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit]
    def finish(span: Span): UIO[Unit]
    def log(fields: Map[String, _]): UIO[Unit]
    def log(msg: String): UIO[Unit]
    def root(operation: String): UIO[Span]
    def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A]
    def span(span: Span, operation: String): UIO[Span]
    def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A]
    def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A]
    def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A]
  }

  lazy val noop: URLayer[Clock, OpenTracing] = live(NoopTracerFactory.create())

  def live(tracer: Tracer, rootOperation: String = "ROOT"): URLayer[Clock, OpenTracing] =
    ZLayer.fromManaged(managed(tracer, rootOperation))

  def managed(tracer0: Tracer, rootOperation: String): URManaged[Clock, OpenTracing.Service] =
    ZManaged.make(
      for {
        span   <- UIO(tracer0.buildSpan(rootOperation).start())
        ref    <- FiberRef.make(span)
        clock  <- ZIO.service[Clock.Service]
        micros = clock.currentTime(TimeUnit.MICROSECONDS)
      } yield new OpenTracing.Service { self =>
        val tracer: Tracer = tracer0

        val currentSpan: FiberRef[Span] = ref

        def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
          for {
            _ <- UIO(span.setTag("error", true)).when(tagError)
            _ <- UIO(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)
          } yield ()

        def finish(span: Span): UIO[Unit] = micros.map(span.finish)

        def log(msg: String): UIO[Unit] =
          currentSpan.get.zipWith(micros)((span, now) => span.log(now, msg)).unit

        def log(fields: Map[String, _]): UIO[Unit] =
          currentSpan.get.zipWith(micros)((span, now) => span.log(now, fields.asJava)).unit

        def root(operation: String): UIO[Span] = UIO(tracer.buildSpan(operation).start())

        def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setBaggageItem(key, value))

        def span(span: Span, operation: String): UIO[Span] =
          for {
            old   <- currentSpan.get
            child <- UIO(tracer.buildSpan(operation).asChildOf(old).start())
          } yield child

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))
      }
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

  def context: URIO[OpenTracing, SpanContext] =
    ZIO.accessM(_.get.currentSpan.get.map(_.context))

  def getBaggageItem(key: String): URIO[OpenTracing, Option[String]] =
    getSpan.map(_.getBaggageItem(key)).map(Option(_))

  def inject[C <: AnyRef](format: Format[C], carrier: C): URIO[OpenTracing, Unit] =
    for {
      service <- ZIO.service[OpenTracing.Service]
      span    <- service.currentSpan.get
      _       <- ZIO.effectTotal(service.tracer.inject(span.context(), format, carrier))
    } yield ()

  def log(msg: String): URIO[OpenTracing, Unit] =
    ZIO.accessM(_.get.log(msg))

  def log(fields: Map[String, _]): URIO[OpenTracing, Unit] =
    ZIO.accessM(_.get.log(fields))

  def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.setBaggageItem(zio, key, value))

  def setBaggageItem(key: String, value: String): URIO[OpenTracing, Unit] = setBaggageItem(ZIO.unit, key, value)

  def spanFrom[R, R1 <: R with OpenTracing, E, Span, C <: AnyRef](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, Span],
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R1, E, Span] =
    ZIO.service[OpenTracing.Service].flatMap { service =>
      Task(service.tracer.extract(format, carrier))
        .fold(_ => None, Option.apply)
        .flatMap {
          case None => zio
          case Some(spanCtx) =>
            zio.span(service)(
              service.tracer.buildSpan(operation).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }
    }

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag(key: String, value: String): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)

  def tag(key: String, value: Int): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)

  def tag(key: String, value: Boolean): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)

  private def getSpan: URIO[OpenTracing, Span] =
    ZIO.accessM(_.get.currentSpan.get)

}
