package zio.telemetry.opentracing

import io.opentracing.propagation.Format
import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import zio.URIO
import zio.ZIO
import zio.clock.Clock
import zio.telemetry.Telemetry

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
    ZIO.accessM { _.telemetry.spanFrom(format, carrier, zio, opName, tagError, logError) }

  def inject[C <: Object](format: Format[C], carrier: C): ZIO[OpenTracing, Nothing, Unit] =
    ZIO.accessM { _.telemetry.inject(format, carrier) }

  def context: ZIO[OpenTracing.Service, Nothing, SpanContext] =
    ZIO.accessM { _.telemetry.context }

  def getBaggageItem(key: String): URIO[OpenTracing, Option[String]] =
    ZIO.accessM { _.telemetry.getBaggageItem(key) }

  def setBaggageItem(key: String, value: String): URIO[OpenTracing, Unit] =
    ZIO.accessM { _.telemetry.setBaggageItem(key, value) }

  def tag(key: String, value: String): URIO[OpenTracing, Unit] =
    ZIO.accessM { _.telemetry.tag(key, value) }

  def tag(key: String, value: Int): URIO[OpenTracing, Unit] =
    ZIO.accessM { _.telemetry.tag(key, value) }

  def tag(key: String, value: Boolean): URIO[OpenTracing, Unit] =
    ZIO.accessM { _.telemetry.tag(key, value) }

  def log(msg: String): ZIO[Clock with OpenTracing, Nothing, Unit] =
    ZIO.accessM { _.telemetry.log(msg) }

  def log(fields: Map[String, _]): ZIO[Clock with OpenTracing, Nothing, Unit] =
    ZIO.accessM { _.telemetry.log(fields) }

}
