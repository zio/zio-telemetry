package zio.telemetry.opentracing.example.http

import io.circe._
import io.circe.generic.semiauto._

final case class Status(name: String, status: String)

object Status {
  implicit val decoder: Decoder[Status] = deriveDecoder
  implicit val encoder: Encoder[Status] = deriveEncoder

  final def up(component: String): Status   = Status(component, status = "up")
  final def down(component: String): Status = Status(component, status = "down")

}
