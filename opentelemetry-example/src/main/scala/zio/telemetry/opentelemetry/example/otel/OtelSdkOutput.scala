package zio.telemetry.opentelemetry.example.otel

import zio._

sealed trait OtelSdkOutput
object OtelSdkOutput {
  case object Stdout extends OtelSdkOutput
  case object JaegerSeq extends OtelSdkOutput

  def parse(value: String): OtelSdkOutput = {
    value match {
      case "jaeger-seq" => OtelSdkOutput.JaegerSeq
      case _ => OtelSdkOutput.Stdout
    }
  }

  def live: URLayer[ZIOAppArgs, OtelSdkOutput] =
    ZLayer(for {
      args <- ZIO.serviceWith[ZIOAppArgs](_.getArgs)
      otelSdkOutput = args.headOption.map(OtelSdkOutput.parse).getOrElse(OtelSdkOutput.Stdout)
    } yield otelSdkOutput)
}
