package zio.telemetry.opentracing.example.http

import zio.http._
import zio.Console.printLine
import zio._
import zio.telemetry.opentracing.example.config.AppConfig

case class BackendHttpServer(config: AppConfig, httpApp: BackendHttpApp) {

  def start: ZIO[Any, Throwable, Nothing] =
    printLine(s"Starting BackendHttpServer on port ${config.backend.port}") *>
      Server.serve(httpApp.routes).provide(Server.defaultWithPort(config.backend.port))

}

object BackendHttpServer {

  val live: URLayer[AppConfig with BackendHttpApp, BackendHttpServer] =
    ZLayer.fromFunction(BackendHttpServer.apply _)

}
