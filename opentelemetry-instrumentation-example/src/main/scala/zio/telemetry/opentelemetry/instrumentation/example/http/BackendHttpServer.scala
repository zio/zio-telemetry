package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

case class BackendHttpServer(config: AppConfig, httpServerApp: BackendHttpApp) {

  def start: Task[Nothing] =
    ZIO.logInfo(s"Starting HttpServer on port ${config.backend.port}") *>
      Server.serve(httpServerApp.routes).provide(Server.defaultWithPort(config.backend.port))

}

object BackendHttpServer {

  val live: URLayer[AppConfig with BackendHttpApp, BackendHttpServer] =
    ZLayer.fromFunction(BackendHttpServer.apply _)

}
