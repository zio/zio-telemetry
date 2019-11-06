package zio.telemetry.example.config

import zio.telemetry.example.config.ProxyConfig.{ BackendUrl, TracerHost }

final case class ProxyConfig(host: String, port: Int, backend: BackendUrl, tracer: TracerHost)

object ProxyConfig {

  final case class BackendUrl(url: String) extends AnyVal

  final case class TracerHost(host: String) extends AnyVal

}
