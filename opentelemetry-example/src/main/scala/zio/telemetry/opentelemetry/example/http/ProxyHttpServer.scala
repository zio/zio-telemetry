package zio.telemetry.opentelemetry.example.http

import zio.http._
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

case class ProxyHttpServer(config: AppConfig, httpApp: ProxyHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    ZIO.logInfo(s"Starting ProxyHttpServer on port ${config.proxy.port}") *>
      Server.serve(httpApp.routes).provide(Server.defaultWithPort(config.proxy.port))

}

object ProxyHttpServer {

  val live: URLayer[AppConfig with ProxyHttpApp, ProxyHttpServer] =
    ZLayer.fromFunction(ProxyHttpServer.apply _)

}
