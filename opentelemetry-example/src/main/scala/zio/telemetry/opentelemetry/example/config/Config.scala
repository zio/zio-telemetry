package zio.telemetry.opentelemetry.example.config

import Config._
import sttp.model.Uri

final case class Config(proxy: ProxyConfig, backend: BackendConfig, tracer: TracerHost)

object Config {

  final case class ProxyConfig(host: Uri)

  final case class BackendConfig(host: Uri)

  final case class TracerHost(host: String) extends AnyVal

}
