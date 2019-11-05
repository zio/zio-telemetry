package zio.telemetry.example.config

import zio.telemetry.example.config.Config.{ BackendConfig, ProxyConfig, TracerConfig }

final case class Config(proxy: ProxyConfig, backend: BackendConfig, tracer: TracerConfig)

object Config {

  final case class ProxyConfig(host: String, port: Int)

  final case class BackendConfig(url: String)

  final case class TracerConfig(host: String)

}
