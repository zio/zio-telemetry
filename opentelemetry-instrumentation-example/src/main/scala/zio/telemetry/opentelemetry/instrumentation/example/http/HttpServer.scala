package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zhttp.service.Server
import zio.Console.printLine
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

case class HttpServer(config: AppConfig, httpServerApp: HttpServerApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    for {
      _     <- Server.start(config.server.port, httpServerApp.routes)
      _     <- printLine(s"HttpServer started on port ${config.server.port}")
      never <- ZIO.never
    } yield never

}

object HttpServer {

  val live: URLayer[AppConfig with HttpServerApp, HttpServer] =
    ZLayer.fromFunction(HttpServer.apply _)

}
