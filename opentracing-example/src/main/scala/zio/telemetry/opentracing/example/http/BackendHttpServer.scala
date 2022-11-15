package zio.telemetry.opentracing.example.http

import zhttp.service.Server
import zio.Console.printLine
import zio._
import zio.telemetry.opentracing.example.config.AppConfig

case class BackendHttpServer(config: AppConfig, httpApp: BackendHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    for {
      _     <- Server.start(config.backend.port, httpApp.routes)
      _     <- printLine(s"BackendHttpServer started on port ${config.backend.port}")
      never <- ZIO.never
    } yield never

}

object BackendHttpServer {

  val live: URLayer[AppConfig with BackendHttpApp, BackendHttpServer] =
    ZLayer.fromFunction(BackendHttpServer.apply _)

}
