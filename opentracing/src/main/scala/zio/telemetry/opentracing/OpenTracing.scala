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
    def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R, E, A]
    def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R, E, A]
    def root[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A]
    def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A]
    def span[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A]
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

        def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R, E, A] =
          zio <* currentSpan.get.zipWith(micros)((span, now) => span.log(now, fields.asJava))

        def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R, E, A] =
          zio <* currentSpan.get.zipWith(micros)((span, now) => span.log(now, msg))

        def root[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A] =
          for {
            root    <- UIO(tracer.buildSpan(operation).start())
            current <- currentSpan.get
            _       <- currentSpan.set(root)
            res <- zio
                    .catchAllCause(c => error(root, c, tagError, logError) *> IO.done(Exit.Failure(c)))
                    .ensuring(finish(root) *> currentSpan.set(current))
          } yield res

        def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setBaggageItem(key, value))

        def span[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A] =
          for {
            current <- currentSpan.get
            child   <- UIO(tracer.buildSpan(operation).asChildOf(current).start())
            _       <- currentSpan.set(child)
            res <- zio
                    .catchAllCause(c => error(child, c, tagError, logError) *> IO.done(Exit.Failure(c)))
                    .ensuring(finish(child) *> currentSpan.set(current))
          } yield res

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))
      }
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

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
            for {
              current <- service.currentSpan.get
              span    <- UIO(service.tracer.buildSpan(operation).asChildOf(spanCtx).start())
              _       <- service.currentSpan.set(span)
              res <- zio
                      .catchAllCause(c => service.error(span, c, tagError, logError) *> IO.done(Exit.Failure(c)))
                      .ensuring(service.finish(span) *> service.currentSpan.set(current))
            } yield res
        }
    }

  def context: URIO[OpenTracing, SpanContext] =
    ZIO.accessM(_.get.currentSpan.get.map(_.context))

  def getBaggageItem(key: String): URIO[OpenTracing, Option[String]] =
    for {
      service <- ZIO.service[OpenTracing.Service]
      span    <- service.currentSpan.get
      res     <- ZIO.effectTotal(span.getBaggageItem(key)).map(Option(_))
    } yield res

  def inject[C <: AnyRef](format: Format[C], carrier: C): URIO[OpenTracing, Unit] =
    for {
      service <- ZIO.service[OpenTracing.Service]
      span    <- service.currentSpan.get
      _       <- ZIO.effectTotal(service.tracer.inject(span.context(), format, carrier))
    } yield ()

  def log(msg: String): URIO[OpenTracing, Unit] = log(ZIO.unit, msg)

  def log(fields: Map[String, _]): URIO[OpenTracing, Unit] = log(ZIO.unit, fields)

  def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.log(zio, fields))

  def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.log(zio, msg))

  def root[R, E, A](
    zio: ZIO[R, E, A],
    operation: String,
    tagError: Boolean = false,
    logError: Boolean = false
  ): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.root(zio, operation, tagError, logError))

  def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.setBaggageItem(zio, key, value))

  def setBaggageItem(key: String, value: String): URIO[OpenTracing, Unit] = setBaggageItem(ZIO.unit, key, value)

  def span[R, E, A](
    zio: ZIO[R, E, A],
    operation: String,
    tagError: Boolean = false,
    logError: Boolean = false
  ): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.span(zio, operation, tagError, logError))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
    ZIO.accessM(_.get.tag(zio, key, value))

  def tag(key: String, value: String): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)

  def tag(key: String, value: Int): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)

  def tag(key: String, value: Boolean): URIO[OpenTracing, Unit] = tag(ZIO.unit, key, value)
}
