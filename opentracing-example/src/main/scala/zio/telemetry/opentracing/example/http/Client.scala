package zio.telemetry.opentracing.example.http

import sttp.client3._
import sttp.client3.asynchttpclient.zio._
import sttp.client3.ziojson._
import sttp.model.Uri
import zio.Task

object Client {
  private val backend = AsyncHttpClientZioBackend()

  def status(
    uri: Uri,
    headers: Map[String, String]
  ): Task[Response[Either[ResponseException[String, String], Status]]] =
    backend.flatMap { backend =>
      basicRequest.get(uri).headers(headers).response(asJson[Status]).send(backend)
    }
}
