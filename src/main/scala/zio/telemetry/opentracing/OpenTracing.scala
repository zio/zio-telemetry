package zio.telemetry.opentracing

import io.opentracing.propagation.Format
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import zio.URIO
import zio.ZIO
import zio.clock.Clock
import zio.telemetry._
import zio.Task
import java.util.concurrent.TimeUnit
import zio.UIO
import scala.jdk.CollectionConverters._

trait OpenTracing extends Telemetry {
  override def telemetry: OpenTracing.Service
}

object OpenTracing {

  trait Service extends Telemetry.Service {
    override type A = Span
    private[opentracing] val tracer: Tracer
  }

  def spanFrom[R, R1 <: R with Clock with OpenTracing, E, A, C <: Object](
    format: Format[C],
    carrier: C,
    zio: ZIO[R, E, A],
    opName: String,
    tagError: Boolean = true,
    logError: Boolean = true
  ): ZIO[R1, E, A] =
    ZIO.accessM { env =>
      val telemetry = env.telemetry
      Task(telemetry.tracer.extract(format, carrier))
        .fold(_ => None, Option.apply)
        .flatMap {
          case None => zio
          case Some(spanCtx) =>
            zio.span(telemetry)(
              telemetry.tracer.buildSpan(opName).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }
    }

  def inject[C <: Object](format: Format[C], carrier: C): ZIO[OpenTracing, Nothing, Unit] =
    ZIO.accessM { env =>
      val telemetry = env.telemetry
      telemetry.currentSpan.get.flatMap { span =>
        ZIO.effectTotal(telemetry.tracer.inject(span.context(), format, carrier)).unit
      }
    }

  def context: ZIO[OpenTracing, Nothing, SpanContext] =
    ZIO.accessM { _.telemetry.currentSpan.get.map(_.context) }

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
    ZIO.accessM { _.telemetry.currentSpan.get }

  private def getCurrentTimeMicros: ZIO[Clock, Nothing, Long] =
    ZIO.accessM(_.clock.currentTime(TimeUnit.MICROSECONDS))
}
