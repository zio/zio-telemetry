package zio.telemetry.example

import io.jaegertracing.Configuration
import io.jaegertracing.internal.samplers.ConstSampler
import io.jaegertracing.zipkin.ZipkinV2Reporter
import org.apache.http.client.utils.URIBuilder
import zio.ZManaged
import zio.clock.Clock
import zio.telemetry.opentracing.{ managed, OpenTracing }
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

object JaegerTracer {

  def makeService(host: String, serviceName: String): ZManaged[Clock, Throwable, Clock with OpenTracing] = {
    val url           = new URIBuilder().setScheme("http").setHost(host).setPath("/api/v2/spans").build.toString
    val senderBuilder = OkHttpSender.newBuilder.compressionEnabled(true).endpoint(url)

    val tracer = new Configuration(serviceName).getTracerBuilder
      .withSampler(new ConstSampler(true))
      .withReporter(new ZipkinV2Reporter(AsyncReporter.create(senderBuilder.build)))
      .build

    managed(tracer).map { telemetryService =>
      new Clock.Live with OpenTracing {
        override def telemetry: OpenTracing.Service = telemetryService.telemetry
      }
    }
  }
}
