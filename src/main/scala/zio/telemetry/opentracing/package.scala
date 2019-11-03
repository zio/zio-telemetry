package zio.telemetry

import java.util.concurrent.TimeUnit

import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import zio.FiberRef
import zio.Task
import zio.ZIO
import zio.UIO
import zio.URIO
import zio.ZManaged
import zio.clock.Clock

import scala.jdk.CollectionConverters._

package object opentracing {
  def managed(tracer: Tracer, rootOpName: String = "ROOT"): ZManaged[Clock, Nothing, Telemetry.Service[Span]] =
    ZManaged.make(
      for {
        span    <- UIO(tracer.buildSpan(rootOpName).start())
        ref     <- FiberRef.make(span)
        tracer_ = tracer
      } yield new OpenTracingService {
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
      }
    )(_.currentSpan.get.flatMap(span => UIO(span.finish())))

  implicit final class OpenTracingOps(val telemetry: OpenTracingService) extends AnyVal {
    def spanFrom[R, R1 <: R with Clock with Telemetry[Span], E, A, C <: Object](
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
            zio.span(
              telemetry.tracer.buildSpan(opName).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }

    def inject[R, R1 <: R with Clock with Telemetry[Span], E, A, C <: Object](
      format: Format[C],
      carrier: C
    ): ZIO[Telemetry[Span], Nothing, Unit] =
      telemetry.currentSpan.get.flatMap { span =>
        ZIO.effectTotal(telemetry.tracer.inject(span.context(), format, carrier)).unit
      }

    def context: ZIO[Telemetry[Span], Nothing, SpanContext] =
      telemetry.currentSpan.get.map(_.context)

    def getBaggageItem(key: String): ZIO[Telemetry[Span], Nothing, Option[String]] =
      getSpan.map(_.getBaggageItem(key)).map(Option(_))

    def setBaggageItem(key: String, value: String): ZIO[Telemetry[Span], Nothing, Unit] =
      getSpan.flatMap(span => UIO(span.setBaggageItem(key, value))).unit

    def tag(key: String, value: String): ZIO[Telemetry[Span], Nothing, Unit] =
      getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

    def tag(key: String, value: Int): ZIO[Telemetry[Span], Nothing, Unit] =
      getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

    def tag(key: String, value: Boolean): ZIO[Telemetry[Span], Nothing, Unit] =
      getSpan.flatMap(span => UIO(span.setTag(key, value))).unit

    def log(msg: String): ZIO[Clock with Telemetry[Span], Nothing, Unit] =
      for {
        span <- getSpan
        now  <- getCurrentTimeMicros
        _    <- UIO(span.log(now, msg))
      } yield ()

    def log(fields: Map[String, _]): ZIO[Clock with Telemetry[Span], Nothing, Unit] =
      for {
        span <- getSpan
        now  <- getCurrentTimeMicros
        _    <- UIO(span.log(now, fields.asJava))
      } yield ()

    private def getSpan: ZIO[Telemetry[Span], Nothing, Span] =
      ZIO.accessM[Telemetry[Span]](_.telemetry.currentSpan.get)

    // private def getTelemetry: ZIO[Telemetry[Span], Nothing, Telemetry.Service[Span]] =
    //   ZIO.environment[Telemetry[Span]].map(_.telemetry)

    private def getCurrentTimeMicros: ZIO[Clock, Nothing, Long] =
      ZIO.accessM[Clock](_.clock.currentTime(TimeUnit.MICROSECONDS))

  }

  // implicit final class OpenTracingZIOOps[R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {
  //   def spanFrom[R1 <: R with Clock with Telemetry, C <: Object](
  //     format: Format[C],
  //     carrier: C,
  //     opName: String,
  //     tagError: Boolean = true,
  //     logError: Boolean = true
  //   ): ZIO[R1, E, A] =
  //     ZIO.accessM(_.telemetry.spanFrom(format, carrier, zio, opName, tagError, logError))
  // }
}
