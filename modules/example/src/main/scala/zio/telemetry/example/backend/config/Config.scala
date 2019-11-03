package zio.telemetry.example.backend.config

import zio.telemetry.example.backend.config.Config._

final case class Config(api: ApiConfig, tracer: TracerConfig)

object Config {
  final case class ApiConfig(host: String, port: Int)
  final case class TracerConfig(host: String)
}
