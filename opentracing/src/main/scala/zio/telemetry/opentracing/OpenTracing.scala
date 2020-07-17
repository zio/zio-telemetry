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
    def root(operation: String): UIO[Span]
    def span(span: Span, operation: String): UIO[Span]
    def finish(span: Span): UIO[Unit]
    def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit]
    def log(msg: String): UIO[Unit]
    def log(fields: Map[String, _]): UIO[Unit]
  }

  val noop: URLayer[Clock, OpenTracing] =
    live(NoopTracerFactory.create())

  /**
   * Creates a OpenTracing layer which will be constructed each time it's provided by provideLayer (fresh)
   *
   * On each construction the current value of the `FiberRef[A]` is extracted to a `Entrypoint` which contains
   * a name plus a SpanContext. Therefore if you use `FiberRef.locally` and provide a local context and inside
   * locally you use `provideLayer` the OpenTracing layer will be scoped to that `Entrypoint`.
   *
   * This can be plugged at points in your application where messages come in from a async boundary like a http server,
   * kafka consumer, etc.
   *
   * @param tracer A instance of a OpenTracing tracer
   * @param refEntrypoint A FiberRef with the context
   * @param entrypointExtractor A `EntrypointExtractor` which extracts a `Entrypoint` from `A`
   * @tparam A The generic context
   * @return OpenTracing layer
   */
  def fromEntrypoint[A](
    tracer: Tracer,
    refEntrypoint: FiberRef[A],
    entrypointExtractor: EntrypointExtractor[A]
  ): URLayer[Clock, OpenTracing] =
    ZLayer.fromManaged(managedFromEntrypoint(tracer, refEntrypoint, entrypointExtractor)).fresh

  def live(tracer: Tracer, rootOperation: String = "ROOT"): URLayer[Clock, OpenTracing] =
    ZLayer.fromManaged(managed(tracer, rootOperation))

  def managedFromEntrypoint[A](
    tracer0: Tracer,
    refEntrypoint: FiberRef[A],
    entrypointExtractor: EntrypointExtractor[A]
  ): URManaged[Clock, OpenTracing.Service] =
    ZManaged.make(
      for {
        current    <- refEntrypoint.get
        entrypoint = entrypointExtractor.extract(tracer0, current)
        span       <- UIO(tracer0.buildSpan(entrypoint.name).asChildOf(entrypoint.spanContext).start())
        ref        <- FiberRef.make(span)
        clock      <- ZIO.access[Clock](_.get)
      } yield mkService(tracer0, ref, clock)
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

  def managed(tracer0: Tracer, rootOperation: String): URManaged[Clock, OpenTracing.Service] =
    ZManaged.make(
      for {
        span  <- UIO(tracer0.buildSpan(rootOperation).start())
        ref   <- FiberRef.make(span)
        clock <- ZIO.access[Clock](_.get)
      } yield mkService(tracer0, ref, clock)
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

  private def mkService(tracer0: Tracer, ref: FiberRef[Span], clock: Clock.Service): OpenTracing.Service =
    new OpenTracing.Service {
      override val tracer: Tracer              = tracer0
      override val currentSpan: FiberRef[Span] = ref

      override def root(operation: String): UIO[Span] =
        UIO(tracer.buildSpan(operation).start())

      override def span(span: Span, operation: String): UIO[Span] =
        for {
          old   <- currentSpan.get
          child <- UIO(tracer.buildSpan(operation).asChildOf(old).start())
        } yield child

      override def finish(span: Span): UIO[Unit] =
        clock.currentTime(TimeUnit.MICROSECONDS).map(span.finish)

      override def error(span: Span, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit] =
        UIO(span.setTag("error", true)).when(tagError) *>
          UIO(span.log(Map("error.object" -> cause, "stack" -> cause.prettyPrint).asJava)).when(logError)

      override def log(msg: String): UIO[Unit] =
        currentSpan.get
          .zipWith(getCurrentTimeMicros) { (span, now) =>
            span.log(now, msg)
          }
          .unit

      override def log(fields: Map[String, _]): UIO[Unit] =
        currentSpan.get
          .zipWith(getCurrentTimeMicros) { (span, now) =>
            span.log(now, fields.asJava)
          }
          .unit

      private def getCurrentTimeMicros: UIO[Long] =
        clock.currentTime(TimeUnit.MICROSECONDS)
    }

  def spanFrom[R, R1 <: R with OpenTracing, E, Span, C <: AnyRef](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, Span],
    operation: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R1, E, Span] =
    ZIO.access[OpenTracing](_.get).flatMap { service =>
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

  def inject[C <: AnyRef](format: Format[C], carrier: C): URIO[OpenTracing, Unit] =
    for {
      service <- ZIO.access[OpenTracing](_.get)
      span    <- service.currentSpan.get
      _       <- ZIO.effectTotal(service.tracer.inject(span.context(), format, carrier))
    } yield ()

  def context: URIO[OpenTracing, SpanContext] =
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

  def log(msg: String): URIO[OpenTracing, Unit] =
    ZIO.accessM(_.get.log(msg))

  def log(fields: Map[String, _]): URIO[OpenTracing, Unit] =
    ZIO.accessM(_.get.log(fields))

  private def getSpan: URIO[OpenTracing, Span] =
    ZIO.accessM(_.get.currentSpan.get)

}

final case class Entrypoint(name: String, spanContext: SpanContext)

trait EntrypointExtractor[A] {
  def extract(tracer: Tracer, context: A): Entrypoint
}
