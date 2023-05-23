package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http.{ Request, URL }
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig

import java.nio.charset.StandardCharsets

case class HttpClient(backend: zio.http.Client, config: AppConfig) {

  private val backendUrl =
    URL
      .decode(s"http://${config.server.host}:${config.server.port}")
      .left
      .map(new IllegalArgumentException(_))

  def health: Task[String] =
    for {
      url      <- ZIO.fromEither(backendUrl)
      request   = Request.get(url.withPath("health"))
      response <- backend.request(request)
      result   <- response.body.asString(StandardCharsets.UTF_8)
    } yield result

}

object HttpClient {

  val live: RLayer[AppConfig with zio.http.Client, HttpClient] =
    ZLayer.fromFunction(HttpClient.apply _)

}
