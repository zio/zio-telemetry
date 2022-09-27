package zio.telemetry.opentelemetry.example.http

import zhttp.service.Server
import zio.Console.printLine
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

case class ProxyHttpServer(config: AppConfig, httpApp: ProxyHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    for {
      _     <- Server.start(config.proxy.port, httpApp.routes)
      _     <- printLine(s"ProxyHttpServer started on port ${config.backend.port}")
      never <- ZIO.never
    } yield never

}

object ProxyHttpServer {

  val live: URLayer[AppConfig with ProxyHttpApp, ProxyHttpServer] =
    ZLayer.fromFunction(ProxyHttpServer.apply _)

}
