package zio.telemetry.opentelemetry

import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.api.{OpenTelemetry => JOpenTelemetry}
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.context.{ContextPropagator, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.trace.Tracer
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object OpenTelemetryTest extends ZIOSpecDefault {

  // TODO: move to testkit module
  class OpenTelemetryTestKit(
    val ctxStorage: ContextStorage,
    underlying: OpenTelemetrySdk,
    ctxPropagator: ContextPropagator = ContextPropagator.default
  ) extends OpenTelemetry {
    override def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(Context.current())(zio)

    override def propagate[C](carrier: OutgoingContextCarrier[C])(implicit trace: Trace): UIO[Unit] =
      ctxStorage.get.map(ctxPropagator.instance.inject(_, carrier.kernel, carrier)).unit

    override def continue[R, E, A, C](
      carrier: IncomingContextCarrier[C]
    )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(ctxPropagator.instance.extract(Context.root, carrier.kernel, carrier))(zio)

    override val baggage: Baggage =
      Baggage.make(ctxStorage)

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        def getCurrentContext(implicit trace: Trace): UIO[Context] =
          ctxStorage.get

        def asJava: JOpenTelemetry =
          underlying
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
        } yield new OpenTelemetryTestKit(ctxStorage, underlying)
      )

  }

  val inMemoryTracer: UIO[(InMemorySpanExporter, JTracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("OpenTelemetryTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with JTracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

  def zioFiberRefCtxStorageLayer: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def javaOtelThreadLocalLayer: ULayer[ContextStorage] =
    ZLayer.succeed(ContextStorage.JavaOtelThreadLocal)

  def tracerMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracer with InMemorySpanExporter with JTracer] =
    inMemoryTracerLayer >>> (tracerLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracerLiveLayer(logAnnotated: Boolean = false): URLayer[JTracer with ContextStorage, Tracer] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        jtracer    <- ZIO.service[JTracer]
        tracer     <- Tracer.scoped(jtracer, ctxStorage, logAnnotated)
      } yield tracer
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
          suite("tracer")(
            ctxStoragesMap.map { case (testName, ctxStorageLayer) =>
              test(testName) {
                val carrier: scala.collection.mutable.Map[String, String] =
                  scala.collection.mutable.Map().empty

                for {
                  openTelemetry <- ZIO.service[OpenTelemetry]
                  tracer        <- ZIO.service[Tracer]

                  _ <- openTelemetry.propagate(OutgoingContextCarrier.default(carrier)) @@
                         tracer.aspects.span("foo") @@
                         tracer.aspects.root("ROOT")

                  _ <- ZIO.unit @@
                         tracer.aspects.span("baz") @@
                         tracer.aspects.span("bar") @@
                         openTelemetry.aspects.continue(IncomingContextCarrier.default(carrier))

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
                tracerMockLayer()
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

                  thing <- openTelemetry.baggage.get("some") @@
                             openTelemetry.aspects.continue(IncomingContextCarrier.default(kernel))
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
