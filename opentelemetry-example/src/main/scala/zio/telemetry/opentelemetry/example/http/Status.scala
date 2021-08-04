package zio.telemetry.opentelemetry.example.http

import zio.json.{ DeriveJsonCodec, JsonCodec }

final case class Status(name: String, status: String)

object Status {
  implicit val codec: JsonCodec[Status] = DeriveJsonCodec.gen[Status]

  final def up(component: String): Status   = Status(component, status = "up")
  final def down(component: String): Status = Status(component, status = "down")

}
