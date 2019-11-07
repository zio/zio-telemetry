package zio.telemetry.example.config

import zio.telemetry.example.config.Config.ProxyConfig

final case class Config(proxy: ProxyConfig)

object Config {

  final case class ProxyConfig(host: String, port: Int, backend: BackendUrl, tracer: TracerHost)

  final case class BackendUrl(url: String) extends AnyVal

  final case class TracerHost(host: String) extends AnyVal

}
