package zio.telemetry.opentracing.example.http

import zio.json._

final case class Status(name: String, status: String)

object Status {
  implicit val decoder: JsonDecoder[Status] = DeriveJsonDecoder.gen[Status]
  implicit val encoder: JsonEncoder[Status] = DeriveJsonEncoder.gen[Status]

  final def up(component: String): Status   = Status(component, status = "up")
  final def down(component: String): Status = Status(component, status = "down")
}
