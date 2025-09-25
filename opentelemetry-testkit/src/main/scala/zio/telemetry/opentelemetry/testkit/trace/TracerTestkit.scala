package zio.telemetry.opentelemetry.testkit.trace

import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.trace.Tracer

import scala.jdk.CollectionConverters._

trait TracerTestkit {

  def getFinishedSpans(implicit trace: Trace): UIO[List[SpanData]]

  def resetFinishedSpans(implicit trace: Trace): Task[Unit]

  def getTracer(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  )(implicit trace: Trace): Task[Tracer]

  trait UnsafeAPI {

    def getTracer(
      instrumentationScopeName: String,
      instrumentationVersion: Option[String] = None,
      schemaUrl: Option[String] = None
    )(implicit trace: Trace): Task[JTracer]

    def getTracerFromJava(jtracer: JTracer, logAnnotated: Boolean = false): Task[Tracer]

    def getTracerProvider: SdkTracerProvider

  }

  def unsafe: UnsafeAPI

}

object TracerTestkit {

  def inMemory(implicit trace: Trace): RLayer[ContextStorage, TracerTestkit] =
    ZLayer {
      for {
        spanExporter   <- ZIO.attempt(InMemorySpanExporter.create())
        spanProcessor  <- ZIO.attempt(SimpleSpanProcessor.create(spanExporter))
        tracerProvider <- ZIO.attempt(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
        ctxStorage     <- ZIO.service[ContextStorage]
      } yield new TracerTestkit {

        override def unsafe: UnsafeAPI =
          new UnsafeAPI {

            override def getTracer(
              instrumentationScopeName: String,
              instrumentationVersion: Option[String] = None,
              schemaUrl: Option[String] = None
            )(implicit trace: Trace): Task[JTracer] = ZIO.attempt {
              val builder = tracerProvider.tracerBuilder(instrumentationScopeName)

              instrumentationVersion.foreach(builder.setInstrumentationVersion)
              schemaUrl.foreach(builder.setSchemaUrl)

              builder.build
            }

            override def getTracerFromJava(jtracer: JTracer, logAnnotated: Boolean = false): Task[Tracer] =
              ZIO.succeed(Tracer.make(jtracer, ctxStorage, logAnnotated))

            override def getTracerProvider: SdkTracerProvider =
              tracerProvider

          }

        override def getFinishedSpans(implicit trace: Trace): UIO[List[SpanData]] =
          for {
            _         <- ZIO.succeed(spanProcessor.forceFlush())
            spanItems <- ZIO.succeed(spanExporter.getFinishedSpanItems.asScala.toList)
          } yield spanItems

        override def resetFinishedSpans(implicit trace: Trace): Task[Unit] =
          ZIO.attempt(spanExporter.reset())

        override def getTracer(
          instrumentationScopeName: String,
          instrumentationVersion: Option[String] = None,
          schemaUrl: Option[String] = None,
          logAnnotated: Boolean = false
        )(implicit trace: Trace): Task[Tracer] =
          for {
            jtracer <- unsafe.getTracer(instrumentationScopeName, instrumentationVersion, schemaUrl)
            tracer  <- unsafe.getTracerFromJava(jtracer, logAnnotated)
          } yield tracer

      }
    }

}
