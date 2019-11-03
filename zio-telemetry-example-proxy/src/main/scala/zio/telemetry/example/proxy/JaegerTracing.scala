package zio.telemetry.example.proxy

import io.jaegertracing.internal.samplers.ConstSampler
import io.jaegertracing.zipkin.ZipkinV2Reporter
import io.opentracing.Tracer
import org.apache.http.client.utils.URIBuilder
import zio.clock.Clock
import zio.telemetry.example.proxy.config.JaegerConfig
import zio.telemetry.opentracing
import zio.telemetry.opentracing.OpenTracing
import zio.{ UIO, ZManaged }
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

object JaegerTracing {

  def makeTracer(config: JaegerConfig): UIO[Tracer] = {
    val url = new URIBuilder().setScheme("http").setHost(config.host).setPath("/api/v2/spans").build.toString

    val sender = OkHttpSender.newBuilder
      .compressionEnabled(true)
      .endpoint(url)
      .build()

    val tracer = new io.jaegertracing.Configuration("zio-proxy").getTracerBuilder
      .withSampler(new ConstSampler(true))
      .withReporter(new ZipkinV2Reporter(AsyncReporter.create(sender)))
      .build()

    UIO.succeed(tracer)
  }

  def makeService(tracer: Tracer): ZManaged[Clock, Throwable, Clock with OpenTracing] =
    opentracing.managed(tracer).map { service =>
      new Clock.Live with OpenTracing {
        override def telemetry: OpenTracing.Service = service.telemetry
      }
    }

}
