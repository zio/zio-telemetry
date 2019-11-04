package zio.telemetry.examples.http

import io.circe.Error
import sttp.client._
import sttp.client.asynchttpclient.zio._
import sttp.client.circe.asJson
import sttp.model.Uri
import zio.ZIO

object Client {

  def status(uri: Uri): ZIO[Any, Throwable, Response[Either[ResponseError[Error], Status]]] =
    AsyncHttpClientZioBackend().flatMap { implicit backend =>
      basicRequest.get(uri).response(asJson[Status]).send()
    }
}
