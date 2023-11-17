package zio.telemetry.opentracing.example.http

import zio.http._
import zio.Console.printLine
import zio.telemetry.opentracing.example.config.AppConfig
import zio._

case class ProxyHttpServer(config: AppConfig, httpApp: ProxyHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    printLine(s"Starting ProxyHttpServer on port ${config.proxy.port}") *>
      Server.serve(httpApp.routes).provide(Server.defaultWithPort(config.proxy.port))

}

object ProxyHttpServer {

  val live: URLayer[AppConfig with ProxyHttpApp, ProxyHttpServer] =
    ZLayer.fromFunction(ProxyHttpServer.apply _)

}
