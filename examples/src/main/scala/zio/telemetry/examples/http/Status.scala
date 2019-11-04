package zio.telemetry.examples.http

import io.circe._
import io.circe.generic.semiauto._

final case class Status(name: String, version: String, status: String)

object Status {
  implicit val statusDecoder: Decoder[Status] = deriveDecoder
  implicit val statusEncoder: Encoder[Status] = deriveEncoder
}
