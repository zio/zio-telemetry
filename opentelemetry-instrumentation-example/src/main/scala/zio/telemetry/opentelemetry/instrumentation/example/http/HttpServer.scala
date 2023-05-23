package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zhttp.service.Server
import zio.Console.printLine
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

case class HttpServer(config: AppConfig, httpServerApp: HttpServerApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    printLine(s"Starting HttpServer on port ${config.server.port}") *>
      Server.start(config.server.port, httpServerApp.routes)

}

object HttpServer {

  val live: URLayer[AppConfig with HttpServerApp, HttpServer] =
    ZLayer.fromFunction(HttpServer.apply _)

}
