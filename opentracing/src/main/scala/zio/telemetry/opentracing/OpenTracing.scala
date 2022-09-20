package zio.telemetry.opentracing

import java.util.concurrent.TimeUnit

import io.opentracing.propagation.Format
import io.opentracing.{ Span, SpanContext, Tracer }
import io.opentracing.noop.NoopTracerFactory
import zio._

import scala.jdk.CollectionConverters._

trait OpenTracing {
  private[opentracing] val tracer: Tracer

  def currentSpan: FiberRef[Span]
  def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit]
  def finish(span: Span): UIO[Unit]
  def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R, E, A]
  def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R, E, A]
  def root[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A]
  def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A]
  def getBaggageItem(key: String): UIO[Option[String]]
  def span[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A]
  def spanFrom[R, E, Span, C](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, Span],
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R, E, Span]
  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A]
  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A]
  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A]
  def inject[C](format: Format[C], carrier: C): UIO[Unit]
}

object OpenTracing {

  lazy val noop: ULayer[OpenTracing] =
    live(NoopTracerFactory.create())

  def live(tracer: Tracer, rootOperation: String = "ROOT"): ULayer[OpenTracing] =
    ZLayer.scoped(scoped(tracer, rootOperation))

  def scoped(tracer0: Tracer, rootOperation: String): URIO[Scope, OpenTracing] =
    ZIO.acquireRelease(
      for {
        span  <- ZIO.succeed(tracer0.buildSpan(rootOperation).start())
        ref   <- FiberRef.make(span)
        micros = Clock.currentTime(TimeUnit.MICROSECONDS)
      } yield new OpenTracing { self =>
        val tracer: Tracer = tracer0

        val currentSpan: FiberRef[Span] = ref

        def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
          for {
            _ <- ZIO.succeed(span.setTag("error", true)).when(tagError)
            _ <- ZIO.succeed(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)
          } yield ()

        def finish(span: Span): UIO[Unit] = micros.map(span.finish)

        def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R, E, A] =
          zio <* currentSpan.get.zipWith(micros)((span, now) => span.log(now, fields.asJava))

        def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R, E, A] =
          zio <* currentSpan.get.zipWith(micros)((span, now) => span.log(now, msg))

        def root[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A] =
          for {
            root    <- ZIO.succeed(tracer.buildSpan(operation).start())
            current <- currentSpan.get
            _       <- currentSpan.set(root)
            res     <- zio
                         .catchAllCause(c => error(root, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                         .ensuring(finish(root) *> currentSpan.set(current))
          } yield res

        def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setBaggageItem(key, value))

        def getBaggageItem(key: String): UIO[Option[String]] =
          for {
            span <- currentSpan.get
            res  <- ZIO.succeed(span.getBaggageItem(key)).map(Option(_))
          } yield res

        def span[R, E, A](zio: ZIO[R, E, A], operation: String, tagError: Boolean, logError: Boolean): ZIO[R, E, A] =
          for {
            current <- currentSpan.get
            child   <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(current).start())
            _       <- currentSpan.set(child)
            res     <- zio
                         .catchAllCause(c => error(child, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                         .ensuring(finish(child) *> currentSpan.set(current))
          } yield res

        def spanFrom[R, E, Span, C](
          format: Format[C],
          carrier: C,
          zio: ZIO[R, E, Span],
          operation: String,
          tagError: Boolean = true,
          logError: Boolean = true
        ): ZIO[R, E, Span] =
          ZIO
            .attempt(tracer.extract(format, carrier))
            .foldZIO(
              _ => zio,
              spanCtx =>
                for {
                  current <- currentSpan.get
                  span    <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(spanCtx).start())
                  _       <- currentSpan.set(span)
                  res     <- zio
                               .catchAllCause(c => error(span, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                               .ensuring(finish(span) *> currentSpan.set(current))
                } yield res
            )

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A] =
          zio <* currentSpan.get.map(_.setTag(key, value))

        def inject[C](format: Format[C], carrier: C): UIO[Unit] =
          for {
            span <- currentSpan.get
            _    <- ZIO.succeed(tracer.inject(span.context(), format, carrier))
          } yield ()
      }
    )(_.currentSpan.get.flatMap(span => ZIO.succeed(span.finish())))

  def spanFrom[R, E, Span, C](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, Span],
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R with OpenTracing, E, Span] =
    ZIO.serviceWithZIO[OpenTracing](_.spanFrom(format, carrier, zio, operation, tagError, logError))

  def context: URIO[OpenTracing, SpanContext] =
    ZIO.serviceWithZIO(_.currentSpan.get.map(_.context))

  def inject[C](format: Format[C], carrier: C): URIO[OpenTracing, Unit] =
    ZIO.serviceWithZIO[OpenTracing](_.inject(format, carrier))

  def log(msg: String): URIO[OpenTracing, Unit] =
    log(ZIO.unit, msg)

  def log(fields: Map[String, _]): URIO[OpenTracing, Unit] =
    log(ZIO.unit, fields)

  def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.log(zio, fields))

  def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.log(zio, msg))

  def root[R, E, A](
    zio: ZIO[R, E, A],
    operation: String,
    tagError: Boolean = false,
    logError: Boolean = false
  ): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.root(zio, operation, tagError, logError))

  def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.setBaggageItem(zio, key, value))

  def setBaggageItem(key: String, value: String): URIO[OpenTracing, Unit] =
    setBaggageItem(ZIO.unit, key, value)

  def getBaggageItem(key: String): URIO[OpenTracing, Option[String]] =
    ZIO.serviceWithZIO[OpenTracing](_.getBaggageItem(key))

  def span[R, E, A](
    zio: ZIO[R, E, A],
    operation: String,
    tagError: Boolean = false,
    logError: Boolean = false
  ): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.span(zio, operation, tagError, logError))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.tag(zio, key, value))

  def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
    ZIO.serviceWithZIO[OpenTracing](_.tag(zio, key, value))

  def tag(key: String, value: String): URIO[OpenTracing, Unit] =
    tag(ZIO.unit, key, value)

  def tag(key: String, value: Int): URIO[OpenTracing, Unit] =
    tag(ZIO.unit, key, value)

  def tag(key: String, value: Boolean): URIO[OpenTracing, Unit] =
    tag(ZIO.unit, key, value)

}
