package zio.telemetry.opentelemetry.http

import io.circe.Error
import sttp.client._
import sttp.client.asynchttpclient.zio._
import sttp.client.circe.asJson
import sttp.model.Uri
import zio.Task

object Client {
  private val backend = AsyncHttpClientZioBackend()

  def status(uri: Uri, headers: Map[String, String]): Task[Response[Either[ResponseError[Error], Status]]] =
    backend.flatMap { implicit backend =>
      basicRequest.get(uri).headers(headers).response(asJson[Status]).send()
    }
}
