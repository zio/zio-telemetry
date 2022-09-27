package zio.telemetry.opentracing.example.http

import sttp.model.Uri
import zhttp.service.Server
import zio.Console.printLine
import zio.telemetry.opentracing.example.config.AppConfig
import zio._

case class ProxyHttpServer(config: AppConfig, httpApp: ProxyHttpApp) {

  private def routes(backendUrl: Uri) = httpApp.statuses(backendUrl)

  def start: ZIO[Any, Throwable, Nothing] =
    for {
      backendUrl <-
        ZIO
          .fromEither(Uri.safeApply(config.backend.host, config.backend.port))
          .mapError(new IllegalArgumentException(_))
      _          <- Server.start(config.proxy.port, routes(backendUrl))
      _          <- printLine(s"ProxyHttpServer started on ${config.proxy.port}")
      never      <- ZIO.never
    } yield never

}

object ProxyHttpServer {

  val live: URLayer[AppConfig with ProxyHttpApp, ProxyHttpServer] =
    ZLayer.fromFunction(ProxyHttpServer.apply _)

}
