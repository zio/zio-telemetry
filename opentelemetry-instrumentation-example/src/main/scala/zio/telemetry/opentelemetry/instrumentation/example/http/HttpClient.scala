package zio.telemetry.opentelemetry.instrumentation.example.http

import sttp.client3._
import sttp.model.Uri
import zio._
import zio.telemetry.opentelemetry.instrumentation.example.Backend
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

case class HttpClient(backend: Backend, config: AppConfig) {

  private val backendUrl =
    Uri
      .safeApply(config.server.host, config.server.port)
      .map(_.withPath("status"))
      .left
      .map(new IllegalArgumentException(_))

  def health: Task[String] =
    for {
      url      <- ZIO.fromEither(backendUrl)
      response <- backend.send(basicRequest.get(url.withPath("health")).response(asStringAlways))
      result    = response.body
    } yield result

}

object HttpClient {

  val live: RLayer[AppConfig with Backend, HttpClient] =
    ZLayer.fromFunction(HttpClient.apply _)

}
