package zio.telemetry.example.proxy.http

final case class StatusResponse(name: String, version: String, status: String)

final case class StatusesResponse(statuses: StatusResponse)
