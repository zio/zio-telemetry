package zio.telemetry.opentracing.example.http

import zio.json._

final case class BackendStatuses(data: List[BackendStatus])

object BackendStatuses {
  implicit val codec: JsonCodec[BackendStatuses] = DeriveJsonCodec.gen[BackendStatuses]
}
