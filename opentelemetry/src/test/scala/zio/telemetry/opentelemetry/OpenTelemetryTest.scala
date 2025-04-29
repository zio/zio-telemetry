package zio.telemetry.opentelemetry

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.context.{ContextPropagator, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object OpenTelemetryTest extends ZIOSpecDefault {

  // TODO: move to testkit module
  class OpenTelemetryTestKit(val underlying: OpenTelemetrySdk, val ctxStorage: ContextStorage) extends OpenTelemetry {

    override def withBaggage(logAnnotated: Boolean): OpenTelemetry =
      new OpenTelemetryTestKit(underlying, ctxStorage) {
        override val baggage: Baggage = Baggage.make(ctxStorage, logAnnotated)
      }

    override def withContextPropagator(propagator: ContextPropagator): OpenTelemetry =
      new OpenTelemetryTestKit(underlying, ctxStorage) {
        override val ctxPropagator = propagator
      }

  }

  object OpenTelemetryTestKit {

    val layer: ZLayer[ContextStorage, Nothing, OpenTelemetry] =
      ZLayer.scoped(
        for {
          ctxStorage <- ZIO.service[ContextStorage]
          underlying <- ZIO.fromAutoCloseable(
                          ZIO.succeed(
                            OpenTelemetrySdk.builder().build()
                          )
                        )
        } yield new OpenTelemetryTestKit(underlying, ctxStorage)
      )

  }

  val inMemoryTracer: UIO[(InMemorySpanExporter, Tracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("OpenTelemetryTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with Tracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

  def zioFiberRefCtxStorageLayer: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def javaOtelThreadLocalLayer: ULayer[ContextStorage] =
    ZLayer.succeed(ContextStorage.JavaOtelThreadLocal)

  def tracingMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracing with InMemorySpanExporter with Tracer] =
    inMemoryTracerLayer >>> (tracingLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracingLiveLayer(logAnnotated: Boolean = false): URLayer[Tracer with ContextStorage, Tracing] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        tracer     <- ZIO.service[Tracer]
        tracing    <- Tracing.scoped(tracer, ctxStorage, logAnnotated)
      } yield tracing
    }

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  val ctxStoragesMap: Map[String, ULayer[ContextStorage]] =
    Map(
      "zio fiber ref"          -> zioFiberRefCtxStorageLayer,
      "java otel thread local" -> javaOtelThreadLocalLayer
    )

  override def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("OpenTelemetry")(
        suite("manual context propagation")(
          suite("tracing")(
            ctxStoragesMap.map { case (testName, ctxStorageLayer) =>
              test(testName) {
                val carrier: scala.collection.mutable.Map[String, String] =
                  scala.collection.mutable.Map().empty

                for {
                  openTelemetry <- ZIO.service[OpenTelemetry]
                  tracing       <- ZIO.service[Tracing]

                  _ <- openTelemetry.propagate(OutgoingContextCarrier.default(carrier)) @@
                         tracing.aspects.span("foo") @@
                         tracing.aspects.root("ROOT")

                  _ <- openTelemetry.continue(IncomingContextCarrier.default(carrier))(
                         ZIO.unit @@
                           tracing.aspects.span("baz") @@
                           tracing.aspects.span("bar")
                       )

                  spans <- getFinishedSpans
                  root   = spans.find(_.getName == "ROOT")
                  foo    = spans.find(_.getName == "foo")
                  bar    = spans.find(_.getName == "bar")
                  baz    = spans.find(_.getName == "baz")
                } yield assert(root)(isSome(anything)) &&
                  assert(foo)(isSome(anything)) &&
                  assert(bar)(isSome(anything)) &&
                  assert(baz)(isSome(anything)) &&
                  assert(foo.get.getParentSpanId)(equalTo(root.get.getSpanId)) &&
                  assert(bar.get.getParentSpanId)(equalTo(foo.get.getSpanId)) &&
                  assert(baz.get.getParentSpanId)(equalTo(bar.get.getSpanId))
              }.provide(
                OpenTelemetryTestKit.layer,
                ctxStorageLayer,
                tracingMockLayer()
              )
            }
          ),
          suite("baggage")(
            ctxStoragesMap.map { case (testName, ctxStorageLayer) =>
              test(testName) {
                val kernel: scala.collection.mutable.Map[String, String] =
                  scala.collection.mutable.Map().empty

                for {
                  openTelemetry <- ZIO.service[OpenTelemetry]

                  _ <- openTelemetry.propagate(OutgoingContextCarrier.default(kernel)) @@
                         openTelemetry.baggage.aspects.set("some", "thing")

                  thing <- openTelemetry.continue(IncomingContextCarrier.default(kernel))(
                             openTelemetry.baggage.get("some")
                           )
                } yield assert(thing)(isSome(equalTo("thing")))
              }.provide(
                OpenTelemetryTestKit.layer,
                ctxStorageLayer
              )
            }
          )
        )
      )
    )

}
