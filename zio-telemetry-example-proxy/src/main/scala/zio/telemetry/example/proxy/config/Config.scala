package zio.telemetry.example.proxy.config

final case class Config(api: ApiConfig, service: ServiceConfig, jaeger: JaegerConfig)
