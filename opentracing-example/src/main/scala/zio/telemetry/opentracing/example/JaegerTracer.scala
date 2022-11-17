package zio.telemetry.opentracing.example

import io.jaegertracing.Configuration
import io.jaegertracing.internal
import io.jaegertracing.internal.samplers.ConstSampler
import io.jaegertracing.zipkin.ZipkinV2Reporter
import io.opentracing.Tracer
import org.apache.http.client.utils.URIBuilder
import zio._
import zio.telemetry.opentracing.example.config.AppConfig
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

object JaegerTracer {

  def live(serviceName: String): RLayer[AppConfig, Tracer] =
    ZLayer {
      for {
        config <- ZIO.service[AppConfig]
        tracer <- makeTracer(config.tracer.host, serviceName)
      } yield tracer
    }

  def makeTracer(host: String, serviceName: String): Task[internal.JaegerTracer] =
    for {
      url           <- ZIO.attempt(new URIBuilder().setScheme("http").setHost(host).setPath("/api/v2/spans").build.toString)
      senderBuilder <- ZIO.attempt(OkHttpSender.newBuilder.compressionEnabled(true).endpoint(url))
      tracer        <- ZIO.attempt(
                         new Configuration(serviceName).getTracerBuilder
                           .withSampler(new ConstSampler(true))
                           .withReporter(new ZipkinV2Reporter(AsyncReporter.create(senderBuilder.build)))
                           .build
                       )
    } yield tracer

}
