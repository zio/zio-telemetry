package zio.telemetry.opentracing.example.config

final case class AppConfig(proxy: ProxyConfig, backend: BackendConfig, tracer: TracerHost)

