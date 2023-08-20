package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.Console.printLine
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

case class HttpServer(config: AppConfig, httpServerApp: HttpServerApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    printLine(s"Starting HttpServer on port ${config.server.port}") *>
      Server.serve(httpServerApp.routes).provide(Server.defaultWithPort(config.server.port))

}

object HttpServer {

  val live: URLayer[AppConfig with HttpServerApp, HttpServer] =
    ZLayer.fromFunction(HttpServer.apply _)

}
