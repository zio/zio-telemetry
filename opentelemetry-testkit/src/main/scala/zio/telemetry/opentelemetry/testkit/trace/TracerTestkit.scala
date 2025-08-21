package zio.telemetry.opentelemetry.testkit.trace

import zio._
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

import scala.jdk.CollectionConverters._
import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.sdk.trace.SdkTracerProvider
import zio.telemetry.opentelemetry.trace.Tracer
import zio.telemetry.opentelemetry.context.internal.ContextStorage

trait TracerTestkit {

  def getFinishedSpans: UIO[List[SpanData]]

  def resetFinishedSpans: Task[Unit]

  def getTracer(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String],
    schemaUrl: Option[String],
    logAnnotated: Boolean = false
  ): Task[Tracer]

  trait UnsafeAPI {

    def getTracer(
      instrumentationScopeName: String,
      instrumentationVersion: Option[String] = None,
      schemaUrl: Option[String] = None
    ): Task[JTracer]

  }

  def unsafe: UnsafeAPI

}

object TracerTestkit {

  def inMemory: RLayer[ContextStorage, TracerTestkit] =
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
              instrumentationVersion: Option[String],
              schemaUrl: Option[String]
            ): Task[JTracer] = ZIO.attempt {
              val builder = tracerProvider.tracerBuilder(instrumentationScopeName)

              instrumentationVersion.foreach(builder.setInstrumentationVersion)
              schemaUrl.foreach(builder.setSchemaUrl)

              builder.build
            }

          }

        override def getFinishedSpans: UIO[List[SpanData]] =
          ZIO.succeed(spanExporter.getFinishedSpanItems.asScala.toList)

        override def resetFinishedSpans: Task[Unit] =
          ZIO.attempt(spanExporter.reset())

        override def getTracer(
          instrumentationScopeName: String,
          instrumentationVersion: Option[String],
          schemaUrl: Option[String],
          logAnnotated: Boolean
        ): Task[Tracer] = ZIO.scoped(
          for {
            jtracer <- unsafe.getTracer(instrumentationScopeName, instrumentationVersion, schemaUrl)
            tracer   = Tracer.make(jtracer, ctxStorage, logAnnotated)
          } yield tracer
        )

      }
    }

}
