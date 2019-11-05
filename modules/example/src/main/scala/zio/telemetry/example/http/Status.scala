package zio.telemetry.example.http

import io.circe._
import io.circe.generic.semiauto._

final case class Status(name: String, version: String, status: String)

object Status {
  implicit val decoder: Decoder[Status] = deriveDecoder
  implicit val encoder: Encoder[Status] = deriveEncoder
}
