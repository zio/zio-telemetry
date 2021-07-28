package zio.telemetry.opentracing.example.http

import zio.json._

final case class Statuses(data: List[Status]) extends AnyVal

object Statuses {
  implicit val decoder: JsonDecoder[Statuses] = DeriveJsonDecoder.gen[Statuses]
  implicit val encoder: JsonEncoder[Statuses] = DeriveJsonEncoder.gen[Statuses]
}
