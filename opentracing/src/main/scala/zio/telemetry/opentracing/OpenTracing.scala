package zio.telemetry.opentracing

import java.util.concurrent.TimeUnit

import io.opentracing.propagation.Format
import io.opentracing.{ Span, SpanContext, Tracer }
import io.opentracing.noop.NoopTracerFactory
import zio._

import scala.jdk.CollectionConverters._

trait OpenTracing {

  @deprecated("The method will become private, use getCurrentSpan instead", "zio-opentracing 2.1.0")
  def currentSpan: FiberRef[Span]

  def getCurrentSpan: UIO[Span]

  @deprecated("The method will be renamed to getCurrentSpanContext", "zio-opentracing 2.1.0")
  def context: UIO[SpanContext]

  def getCurrentSpanContext: UIO[SpanContext]

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

  def scoped(tracer: Tracer, rootOperation: String): URIO[Scope, OpenTracing] = {
    val acquire: URIO[Scope, OpenTracing] = for {
      span         <- ZIO.succeed(tracer.buildSpan(rootOperation).start())
      rootSpan     <- FiberRef.make(span)
      currentMicros = Clock.currentTime(TimeUnit.MICROSECONDS)
    } yield new OpenTracing { self =>
      override val currentSpan: FiberRef[Span] = rootSpan

      override def getCurrentSpan: UIO[Span] = currentSpan.get

      override def context: UIO[SpanContext] =
        getCurrentSpanContext

      override def getCurrentSpanContext: UIO[SpanContext] =
        getCurrentSpan.map(_.context)

      override def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
        for {
          _ <- ZIO.succeed(span.setTag("error", true)).when(tagError)
          _ <- ZIO.succeed(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)
        } yield ()

      override def finish(span: Span): UIO[Unit] =
        currentMicros.flatMap(micros => ZIO.succeed(span.finish(micros)))

      override def log[R, E, A](zio: ZIO[R, E, A], fields: Map[String, _]): ZIO[R, E, A] =
        zio <* getCurrentSpan.zipWith(currentMicros)((span, now) => span.log(now, fields.asJava))

      override def log[R, E, A](zio: ZIO[R, E, A], msg: String): ZIO[R, E, A] =
        zio <* getCurrentSpan.zipWith(currentMicros)((span, now) => span.log(now, msg))

      override def root[R, E, A](
        zio: ZIO[R, E, A],
        operation: String,
        tagError: Boolean,
        logError: Boolean
      ): ZIO[R, E, A] =
        for {
          root    <- ZIO.succeed(tracer.buildSpan(operation).start())
          current <- getCurrentSpan
          _       <- currentSpan.set(root)
          res     <- zio
                       .catchAllCause(c => error(root, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                       .ensuring(finish(root) *> currentSpan.set(current))
        } yield res

      override def setBaggageItem[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
        zio <* getCurrentSpan.map(_.setBaggageItem(key, value))

      override def getBaggageItem(key: String): UIO[Option[String]] =
        for {
          span <- getCurrentSpan
          res  <- ZIO.succeed(span.getBaggageItem(key)).map(Option(_))
        } yield res

      override def span[R, E, A](
        zio: ZIO[R, E, A],
        operation: String,
        tagError: Boolean,
        logError: Boolean
      ): ZIO[R, E, A] =
        for {
          current <- getCurrentSpan
          child   <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(current).start())
          _       <- currentSpan.set(child)
          res     <- zio
                       .catchAllCause(c => error(child, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                       .ensuring(finish(child) *> currentSpan.set(current))
        } yield res

      override def spanFrom[R, E, Span, C](
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
                current <- getCurrentSpan
                span    <- ZIO.succeed(tracer.buildSpan(operation).asChildOf(spanCtx).start())
                _       <- currentSpan.set(span)
                res     <- zio
                             .catchAllCause(c => error(span, c, tagError, logError) *> ZIO.done(Exit.Failure(c)))
                             .ensuring(finish(span) *> currentSpan.set(current))
              } yield res
          )

      override def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: String): ZIO[R, E, A] =
        zio <* getCurrentSpan.map(_.setTag(key, value))

      override def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Int): ZIO[R, E, A] =
        zio <* getCurrentSpan.map(_.setTag(key, value))

      override def tag[R, E, A](zio: ZIO[R, E, A], key: String, value: Boolean): ZIO[R, E, A] =
        zio <* getCurrentSpan.map(_.setTag(key, value))

      override def inject[C](format: Format[C], carrier: C): UIO[Unit] =
        for {
          span <- getCurrentSpan
          _    <- ZIO.succeed(tracer.inject(span.context(), format, carrier))
        } yield ()
    }

    def release(tracing: OpenTracing) =
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.finish()))

    ZIO.acquireRelease(acquire)(release)
  }

  def getCurrentSpan: URIO[OpenTracing, Span] =
    ZIO.serviceWithZIO[OpenTracing](_.getCurrentSpan)

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
    getCurrentSpanContext

  def getCurrentSpanContext: URIO[OpenTracing, SpanContext] =
    ZIO.serviceWithZIO[OpenTracing](_.getCurrentSpanContext)

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
