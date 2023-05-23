package zio.telemetry.opentracing.example.http

import zio.json._

final case class Statuses(data: List[Status])

object Statuses {
  implicit val codec: JsonCodec[Statuses] = DeriveJsonCodec.gen[Statuses]
}
