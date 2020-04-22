package zio.telemetry.opentracing.example.http

import io.circe._
import io.circe.generic.semiauto._

final case class Statuses(data: List[Status]) extends AnyVal

object Statuses {
  implicit val decoder: Decoder[Statuses] = deriveDecoder
  implicit val encoder: Encoder[Statuses] = deriveEncoder
}
