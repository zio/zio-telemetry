package zio.telemetry.opentelemetry.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.example.config.AppConfig

case class BackendHttpServer(config: AppConfig, httpApp: BackendHttpApp) {

  def start: Task[Nothing] =
    ZIO.logInfo(s"Starting BackendHttpServer on port ${config.backend.port}") *>
      Server.serve(httpApp.routes).provide(Server.defaultWithPort(config.backend.port))

}

object BackendHttpServer {

  val live: URLayer[AppConfig with BackendHttpApp, BackendHttpServer] =
    ZLayer.fromFunction(BackendHttpServer.apply _)

}
