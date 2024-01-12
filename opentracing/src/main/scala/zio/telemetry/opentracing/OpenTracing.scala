package zio.telemetry.opentracing

import io.opentracing.noop.NoopTracerFactory
import io.opentracing.propagation.Format
import io.opentracing.{Span, SpanContext, Tracer}
import zio._

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

trait OpenTracing { self =>

  def getCurrentSpanUnsafe(implicit trace: Trace): UIO[Span]

  def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext]

  def error(
    span: Span,
    cause: Cause[_],
    tagError: Boolean = true,
    logError: Boolean = true
  )(implicit trace: Trace): UIO[Unit]

  def finish(span: Span)(implicit trace: Trace): UIO[Unit]

  def log(fields: Map[String, _])(implicit trace: Trace): UIO[Unit]

  def log(msg: String)(implicit trace: Trace): UIO[Unit]

  def root[R, E, A](
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def span[R, E, A](
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def spanFrom[R, E, S, C](
    format: Format[C],
    carrier: C,
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(zio: => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S]

  def tag(key: String, value: String)(implicit trace: Trace): UIO[Unit]

  def tag(key: String, value: Int)(implicit trace: Trace): UIO[Unit]

  def tag(key: String, value: Boolean)(implicit trace: Trace): UIO[Unit]

  def inject[C](format: Format[C], carrier: C)(implicit trace: Trace): UIO[Unit]

  def setBaggageItem(key: String, value: String)(implicit trace: Trace): UIO[Unit]

  def getBaggageItem(key: String)(implicit trace: Trace): UIO[Option[String]]

  object aspects {

    def root(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.root(operation, tagError, logError)(zio)
      }

    def span(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.span(operation, tagError, logError)(zio)
      }

    def spanFrom[C](
      format: Format[C],
      carrier: C,
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.spanFrom(format, carrier, operation, tagError, logError)(zio)
      }

  }

}

object OpenTracing {

  lazy val noop: ULayer[OpenTracing] =
    ZLayer.succeed(NoopTracerFactory.create()) >>> live()

  def live(rootOperation: String = "ROOT"): URLayer[Tracer, OpenTracing] =
    ZLayer.scoped(ZIO.service[Tracer].flatMap(scoped(_, rootOperation)))

  def scoped(tracer: Tracer, rootOperation: String): URIO[Scope, OpenTracing] = {
    val acquire =
      for {
        span         <- ZIO.succeed(tracer.buildSpan(rootOperation).start())
        currentSpan  <- FiberRef.make(span)
        currentMicros = Clock.currentTime(TimeUnit.MICROSECONDS)
      } yield new OpenTracing { self =>
        override def getCurrentSpanUnsafe(implicit trace: Trace): UIO[Span] =
          currentSpan.get

        override def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext] =
          getCurrentSpanUnsafe.map(_.context)

        override def error(
          span: Span,
          cause: Cause[_],
          tagError: Boolean = true,
          logError: Boolean = true
        )(implicit trace: Trace): UIO[Unit] =
          for {
            _        <- ZIO.succeed(span.setTag("error", true)).when(tagError)
            throwable = cause.failureOption match {
                          case Some(t: Throwable) => t
                          case _                  => FiberFailure(cause)
                        }
            _        <- ZIO
                          .succeed(span.log(Map("error.object" -> throwable, "stack" -> cause.prettyPrint).asJava))
                          .when(logError)
          } yield ()

        override def finish(span: Span)(implicit trace: Trace): UIO[Unit] =
          currentMicros.flatMap(micros => ZIO.succeed(span.finish(micros)))

        override def log(fields: Map[String, _])(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.zipWith(currentMicros)((span, now) => span.log(now, fields.asJava)).unit

        override def log(msg: String)(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.zipWith(currentMicros)((span, now) => span.log(now, msg)).unit

        override def root[R, E, A](
          operation: String,
          tagError: Boolean = true,
          logError: Boolean = true
        )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          for {
            root    <- ZIO.succeed(tracer.buildSpan(operation).start())
            current <- getCurrentSpanUnsafe
            _       <- currentSpan.set(root)
            res     <- zio
                         .catchAllCause(c => error(root, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                         .ensuring(finish(root) *> currentSpan.set(current))
          } yield res

        override def span[R, E, A](
          operation: String,
          tagError: Boolean = true,
          logError: Boolean = true
        )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          for {
            current <- getCurrentSpanUnsafe
            child   <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(current).start())
            _       <- currentSpan.set(child)
            res     <- zio
                         .catchAllCause(c => error(child, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                         .ensuring(finish(child) *> currentSpan.set(current))
          } yield res

        override def spanFrom[R, E, S, C](
          format: Format[C],
          carrier: C,
          operation: String,
          tagError: Boolean = true,
          logError: Boolean = true
        )(zio: => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S] =
          ZIO
            .attempt(tracer.extract(format, carrier))
            .foldZIO(
              _ => zio,
              spanCtx =>
                for {
                  current <- getCurrentSpanUnsafe
                  span    <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(spanCtx).start())
                  _       <- currentSpan.set(span)
                  res     <- zio
                               .catchAllCause(c => error(span, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                               .ensuring(finish(span) *> currentSpan.set(current))
                } yield res
            )

        override def tag(key: String, value: String)(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.map(_.setTag(key, value)).unit

        override def tag(key: String, value: Int)(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.map(_.setTag(key, value)).unit

        override def tag(key: String, value: Boolean)(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.map(_.setTag(key, value)).unit

        override def inject[C](format: Format[C], carrier: C)(implicit trace: Trace): UIO[Unit] =
          for {
            span <- getCurrentSpanUnsafe
            _    <- ZIO.succeed(tracer.inject(span.context(), format, carrier))
          } yield ()

        override def setBaggageItem(key: String, value: String)(implicit trace: Trace): UIO[Unit] =
          getCurrentSpanUnsafe.map(_.setBaggageItem(key, value)).unit

        override def getBaggageItem(key: String)(implicit trace: Trace): UIO[Option[String]] =
          for {
            span <- getCurrentSpanUnsafe
            res  <- ZIO.succeed(span.getBaggageItem(key)).map(Option(_))
          } yield res

      }

    def release(tracing: OpenTracing) =
      tracing.getCurrentSpanUnsafe.flatMap(span => ZIO.succeed(span.finish()))

    ZIO.acquireRelease(acquire)(release)
  }

}
