package zio.telemetry.opentelemetry.example.http

import zio.json.{ DeriveJsonCodec, JsonCodec }

final case class Statuses(data: List[Status]) extends AnyVal

object Statuses {
  implicit val codec: JsonCodec[Statuses] = DeriveJsonCodec.gen[Statuses]
}
