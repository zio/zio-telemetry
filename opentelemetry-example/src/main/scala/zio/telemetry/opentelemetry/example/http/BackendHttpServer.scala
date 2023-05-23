package zio.telemetry.opentelemetry.example.http

import zhttp.service.Server
import zio.Console.printLine
import zio._
import zio.telemetry.opentelemetry.example.config.AppConfig

case class BackendHttpServer(config: AppConfig, httpApp: BackendHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    printLine(s"Starting BackendHttpServer on port ${config.backend.port}") *>
      Server.start(config.backend.port, httpApp.routes)

}

object BackendHttpServer {

  val live: URLayer[AppConfig with BackendHttpApp, BackendHttpServer] =
    ZLayer.fromFunction(BackendHttpServer.apply _)

}
