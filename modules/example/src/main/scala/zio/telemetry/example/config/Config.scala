package zio.telemetry.example.config

import zio.telemetry.example.config.Config._

final case class Config(proxy: ProxyConfig, backend: BackendConfig, tracer: TracerHost)

object Config {

  final case class ProxyConfig(host: String, port: Int)

  final case class BackendUrl(url: String) extends AnyVal

  final case class BackendConfig(host: String, port: Int)

  final case class TracerHost(host: String) extends AnyVal

}
