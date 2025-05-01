package zio.telemetry.opentelemetry.example.http

import zio.json._

final case class BackendStatus(name: String, status: String)

object BackendStatus {
  implicit val codec: JsonCodec[BackendStatus] = DeriveJsonCodec.gen[BackendStatus]

  final def up(component: String): BackendStatus   = BackendStatus(component, status = "up")
  final def down(component: String): BackendStatus = BackendStatus(component, status = "down")

}
