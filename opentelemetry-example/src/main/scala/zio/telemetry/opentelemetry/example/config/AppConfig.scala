package zio.telemetry.opentelemetry.example.config

final case class AppConfig(proxy: ProxyConfig, backend: BackendConfig, tracer: TracerHost)

