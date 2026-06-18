package zio.telemetry.opentelemetry.core

import zio._
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage
import zio.telemetry.opentelemetry.core.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import zio.test.Assertion._
import zio.test._

object OpenTelemetryTest extends ZIOSpecDefault {

  val instrumentationScopeName = "OpenTelemetryTest"

  val ctxStorageLayer: ULayer[ContextStorage] =
    OpenTelemetryTestkit.ctxStorageZioFiberRef

  override def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("OpenTelemetry")(
        suite("manual context propagation")(
          suite("tracer")(
            test("zio fiber ref") {
              val carrier: scala.collection.mutable.Map[String, String] =
                scala.collection.mutable.Map().empty

              for {
                openTelemetry <- ZIO.service[OpenTelemetry]
                tracerTestkit <- ZIO.service[TracerTestkit]
                tracer        <- tracerTestkit.getTracer(instrumentationScopeName)

                _ <- openTelemetry.propagate(OutgoingContextCarrier.default(carrier)) @@
                       tracer.aspects.span("foo") @@
                       tracer.aspects.root("ROOT")

                _ <- ZIO.unit @@
                       tracer.aspects.span("baz") @@
                       tracer.aspects.span("bar") @@
                       openTelemetry.aspects.continue(IncomingContextCarrier.default(carrier))

                spans <- tracerTestkit.getFinishedSpans
                root   = spans.find(_.name == "ROOT")
                foo    = spans.find(_.name == "foo")
                bar    = spans.find(_.name == "bar")
                baz    = spans.find(_.name == "baz")
              } yield assert(root)(isSome(anything)) &&
                assert(foo)(isSome(anything)) &&
                assert(bar)(isSome(anything)) &&
                assert(baz)(isSome(anything)) &&
                assert(foo.get.parentSpanId)(equalTo(root.get.spanId)) &&
                assert(bar.get.parentSpanId)(equalTo(foo.get.spanId)) &&
                assert(baz.get.parentSpanId)(equalTo(bar.get.spanId))
            }.provide(
              OpenTelemetryTestkit.sdk(),
              TracerTestkit.inMemory,
              ctxStorageLayer
            )
          ),
          suite("baggage")(
            test("zio fiber ref") {
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
              OpenTelemetryTestkit.sdk(),
              ctxStorageLayer
            )
          )
        )
      )
    )

}
