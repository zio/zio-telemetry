package zio.telemetry.opentracing.example

import sttp.client.{ NothingT, Request, Response, SttpBackend }
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client.ziotelemetry.opentracing.{ ZioTelemetryOpenTracingBackend, ZioTelemetryOpenTracingTracer }
import zio.{ Has, RIO, ZIO, ZLayer }
import zio.clock.Clock
import zio.telemetry.opentracing.OpenTracing

package object http {

  type AppTask[A]    = ZIO[Clock, Throwable, A]
  type Backend[F[_]] = Has[SttpBackend[F, Nothing, NothingT]]
  type TracedBackend = Backend[RIO[OpenTracing, *]]
  type Client        = Has[Client.Service]

  private val sttpTracer = new ZioTelemetryOpenTracingTracer {
    def before[T](request: Request[T, Nothing]): RIO[OpenTracing, Unit] =
      OpenTracing.tag("span.kind", "client") *>
        OpenTracing.tag("http.method", request.method.method) *>
        OpenTracing.tag("http.url", request.uri.toString())

    def after[T](response: Response[T]): RIO[OpenTracing, Unit] =
      OpenTracing.tag("http.status_code", response.code.code)
  }

  def tracedBackend: ZLayer[Any, Throwable, TracedBackend] =
    AsyncHttpClientZioBackend.layer() >>> ZLayer.fromService(backend =>
      new ZioTelemetryOpenTracingBackend[NothingT](backend, sttpTracer)
    )

}
