package zio.telemetry.opentelemetry.example.http

import zio.json._

final case class BackendStatuses(data: List[BackendStatus]) extends AnyVal

object BackendStatuses {
  implicit val codec: JsonCodec[BackendStatuses] = DeriveJsonCodec.gen[BackendStatuses]
}
