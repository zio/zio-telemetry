package zio.telemetry.opentracing

import java.util.concurrent.TimeUnit

import io.opentracing.propagation.Format
import io.opentracing.{ Span, SpanContext, Tracer }
import io.opentracing.noop.NoopTracerFactory
import zio._

import scala.jdk.CollectionConverters._

trait OpenTracing { self =>

  def getCurrentSpan(implicit trace: Trace): UIO[Span]

  def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext]

  def error(
    span: Span,
    cause: Cause[_],
    tagError: Boolean = true,
    logError: Boolean = true
  )(implicit trace: Trace): UIO[Unit]

  def finish(span: Span)(implicit trace: Trace): UIO[Unit]

  def log[R, E, A](fields: Map[String, _])(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def log[R, E, A](msg: String)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def root[R, E, A](
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def span[R, E, A](
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def spanFrom[R, E, S, C](
    format: Format[C],
    carrier: C,
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  )(effect: => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S]

  def tag[R, E, A](key: String, value: String)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def tag[R, E, A](key: String, value: Int)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def tag[R, E, A](key: String, value: Boolean)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def inject[C](format: Format[C], carrier: C)(implicit trace: Trace): UIO[Unit]

  def setBaggageItem[R, E, A](key: String, value: String)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

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

    def setBaggageItem(key: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.setBaggageItem(key, value)(zio)
      }

    def tag(key: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.tag(key, value)(zio)
      }

    def tag(key: String, value: Int): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.tag(key, value)(zio)
      }

    def tag(key: String, value: Boolean): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.tag(key, value)(zio)
      }

    def log(fields: Map[String, _]): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.log(fields)(zio)
      }

    def log(msg: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.log(msg)(zio)
      }

    def inject[C](format: Format[C], carrier: C): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.inject(format, carrier) *> zio
      }

  }

}

object OpenTracing {

  lazy val noop: ULayer[OpenTracing] =
    ZLayer.succeed(NoopTracerFactory.create()) >>> live()

  def live(rootOperation: String = "ROOT"): URLayer[Tracer, OpenTracing] =
    ZLayer.scoped(ZIO.service[Tracer].flatMap(scoped(_, rootOperation)))

  def scoped(tracer: Tracer, rootOperation: String): URIO[Scope, OpenTracing] = {
    val acquire: URIO[Scope, OpenTracing] = for {
      span         <- ZIO.succeed(tracer.buildSpan(rootOperation).start())
      currentSpan  <- FiberRef.make(span)
      currentMicros = Clock.currentTime(TimeUnit.MICROSECONDS)
    } yield new OpenTracing { self =>
      override def getCurrentSpan(implicit trace: Trace): UIO[Span] =
        currentSpan.get

      override def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext] =
        getCurrentSpan.map(_.context)

      override def error(
        span: Span,
        cause: Cause[_],
        tagError: Boolean = true,
        logError: Boolean = true
      )(implicit trace: Trace): UIO[Unit] =
        for {
          _ <- ZIO.succeed(span.setTag("error", true)).when(tagError)
          _ <- ZIO.succeed(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)
        } yield ()

      override def finish(span: Span)(implicit trace: Trace): UIO[Unit] =
        currentMicros.flatMap(micros => ZIO.succeed(span.finish(micros)))

      override def log[R, E, A](fields: Map[String, _])(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        effect <* getCurrentSpan.zipWith(currentMicros)((span, now) => span.log(now, fields.asJava))

      override def log[R, E, A](msg: String)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        effect <* getCurrentSpan.zipWith(currentMicros)((span, now) => span.log(now, msg))

      override def root[R, E, A](
        operation: String,
        tagError: Boolean = true,
        logError: Boolean = true
      )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        for {
          root    <- ZIO.succeed(tracer.buildSpan(operation).start())
          current <- getCurrentSpan
          _       <- currentSpan.set(root)
          res     <- effect
                       .catchAllCause(c => error(root, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                       .ensuring(finish(root) *> currentSpan.set(current))
        } yield res

      override def span[R, E, A](
        operation: String,
        tagError: Boolean = true,
        logError: Boolean = true
      )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        for {
          current <- getCurrentSpan
          child   <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(current).start())
          _       <- currentSpan.set(child)
          res     <- effect
                       .catchAllCause(c => error(child, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                       .ensuring(finish(child) *> currentSpan.set(current))
        } yield res

      override def spanFrom[R, E, S, C](
        format: Format[C],
        carrier: C,
        operation: String,
        tagError: Boolean = true,
        logError: Boolean = true
      )(effect: => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S] =
        ZIO
          .attempt(tracer.extract(format, carrier))
          .foldZIO(
            _ => effect,
            spanCtx =>
              for {
                current <- getCurrentSpan
                span    <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(spanCtx).start())
                _       <- currentSpan.set(span)
                res     <- effect
                             .catchAllCause(c => error(span, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                             .ensuring(finish(span) *> currentSpan.set(current))
              } yield res
          )

      override def tag[R, E, A](key: String, value: String)(effect: => ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] =
        effect <* getCurrentSpan.map(_.setTag(key, value))

      override def tag[R, E, A](key: String, value: Int)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        effect <* getCurrentSpan.map(_.setTag(key, value))

      override def tag[R, E, A](key: String, value: Boolean)(effect: => ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] =
        effect <* getCurrentSpan.map(_.setTag(key, value))

      override def inject[C](format: Format[C], carrier: C)(implicit trace: Trace): UIO[Unit] =
        for {
          span <- getCurrentSpan
          _    <- ZIO.succeed(tracer.inject(span.context(), format, carrier))
        } yield ()

      override def setBaggageItem[R, E, A](key: String, value: String)(effect: => ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] =
        effect <* getCurrentSpan.map(_.setBaggageItem(key, value))

      override def getBaggageItem(key: String)(implicit trace: Trace): UIO[Option[String]] =
        for {
          span <- getCurrentSpan
          res  <- ZIO.succeed(span.getBaggageItem(key)).map(Option(_))
        } yield res

    }

    def release(tracing: OpenTracing) =
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.finish()))

    ZIO.acquireRelease(acquire)(release)
  }

}
