package zio.telemetry.example.http

import io.circe.Error
import sttp.client._
import sttp.client.asynchttpclient.zio._
import sttp.client.circe.asJson
import sttp.model.Uri
import zio.ZIO

object Client {
  private val backend = AsyncHttpClientZioBackend()

  def status(uri: Uri): ZIO[Any, Throwable, Response[Either[ResponseError[Error], Status]]] =
    backend.flatMap { implicit backend =>
      basicRequest.get(uri).response(asJson[Status]).send()
    }
}
