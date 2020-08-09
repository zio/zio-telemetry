package zio.telemetry.opentelemetry.example

import io.grpc.ManagedChannelBuilder
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.exporters.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.trace.Tracer
import zio._
import zio.telemetry.opentelemetry.example.config.{ Config, Configuration }

object JaegerTracer {

  def live(serviceName: String): RLayer[Configuration, Has[Tracer]] =
    ZLayer.fromServiceM((conf: Config) =>
      for {
        tracer         <- UIO(OpenTelemetry.getTracer("zio.telemetry.opentelemetry.example.JaegerTracer"))
        managedChannel <- Task(ManagedChannelBuilder.forTarget(conf.tracer.host).usePlaintext().build())
        _ <- UIO(
              JaegerGrpcSpanExporter
                .newBuilder()
                .setServiceName(serviceName)
                .setChannel(managedChannel)
                .build()
            )
      } yield tracer
    )

}
