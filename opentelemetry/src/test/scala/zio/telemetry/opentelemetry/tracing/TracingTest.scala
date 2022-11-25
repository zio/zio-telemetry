package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.{ Span, SpanId, Tracer }
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import zio._
import zio.telemetry.opentelemetry.context.{ ContextStorage, IngoingContextCarrier, OutgoingContextCarrier }
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.test.Assertion._
import zio.test.{ assert, TestClock, ZIOSpecDefault }

import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

object TracingTest extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, Tracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with Tracer with ContextStorage] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    }) ++ ContextStorage.fiberRef

  val tracingMockLayer: ULayer[Tracing with InMemorySpanExporter with Tracer] =
    inMemoryTracerLayer >>> (Tracing.live ++ inMemoryTracerLayer)

  def getFinishedSpans =
    ZIO
      .service[InMemorySpanExporter]
      .map(_.getFinishedSpanItems.asScala.toList)

  def spec =
    suite("zio opentelemetry")(
      suite("Tracing")(
        creationSpec,
        spansSpec
      )
    )

  def creationSpec =
    suite("creation")(
      test("live") {
        for {
          _             <- ZIO.scoped(Tracing.live.build)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideLayer(inMemoryTracerLayer)
    )

  def spansSpec =
    suite("spans")(
      test("span") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- ZIO.unit @@ span("Child") @@ span("Root")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, String](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            )
        }
      },
      test("root") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- ZIO.unit @@ root("ROOT2") @@ root("ROOT")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "ROOT")
            child  = spans.find(_.getName == "ROOT2")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, String](
                  "parent",
                  _.getParentSpanId,
                  equalTo(SpanId.getInvalid)
                )
              )
            )
        }
      },
      test("inSpan") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            res                       <- inMemoryTracer
            (_, tracer)                = res
            externallyProvidedRootSpan = tracer.spanBuilder("external").startSpan()
            scope                      = externallyProvidedRootSpan.makeCurrent()
            _                         <- ZIO.unit @@ inSpan(externallyProvidedRootSpan, "zio-otel-child")
            _                          = externallyProvidedRootSpan.end()
            _                          = scope.close()
            spans                     <- getFinishedSpans
            child                      = spans.find(_.getName == "zio-otel-child")
          } yield assert(child)(
            isSome(
              hasField[SpanData, String](
                "parent",
                _.getParentSpanId,
                equalTo(externallyProvidedRootSpan.getSpanContext.getSpanId)
              )
            )
          )
        }
      },
      test("scopedEffect") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- tracing.scopedEffect {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     } @@ span[Throwable]("Scoped") @@ span[Throwable]("Root")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, String](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            ) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
        }
      },
      test("scopedEffectTotal") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- tracing.scopedEffectTotal {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       Thread.sleep(10)
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     } @@ span("Scoped") @@ span("Root")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, String](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            ) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
        }
      },
      test("scopedEffectFromFuture") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            result <- tracing.scopedEffectFromFuture { _ =>
                        Future.successful {
                          val span = Span.current()
                          span.addEvent("In legacy code")
                          if (Context.current() == Context.root())
                            throw new RuntimeException("Current context is root!")
                          span.addEvent("Finishing legacy code")
                          1
                        }
                      } @@ span[Throwable]("Scoped") @@ span[Throwable]("Root")
            spans  <- getFinishedSpans
            root    = spans.find(_.getName == "Root")
            scoped  = spans.find(_.getName == "Scoped")
            tags    = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(result)(equalTo(1)) &&
            assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, String](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            ) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
        }
      },
      test("inject - extract roundtrip") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          val carrier: mutable.Map[String, String] = mutable.Map().empty

          for {
            _     <-
              (for {
                _ <-
                  tracing.inject(TraceContextPropagator.default, OutgoingContextCarrier.default(carrier)) @@ span("foo")
                _ <-
                  ZIO.unit @@
                    extractSpan(TraceContextPropagator.default, IngoingContextCarrier.default(carrier), "baz") @@
                    span("bar")
              } yield ()) @@ span("ROOT")
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
            assert(bar.get.getParentSpanId)(equalTo(root.get.getSpanId)) &&
            assert(baz.get.getParentSpanId)(equalTo(foo.get.getSpanId))
        }
      },
      test("setAttribute") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- (for {
                       _ <- tracing.setAttribute("boolean", true)
                       _ <- tracing.setAttribute("int", 1)
                       _ <- tracing.setAttribute("string", "foo")
                       _ <- tracing.setAttribute("booleans", Seq(true, false))
                       _ <- tracing.setAttribute("longs", Seq(1L, 2L))
                       _ <- tracing.setAttribute("strings", Seq("foo", "bar"))
                     } yield ()) @@ span("foo")
            spans <- getFinishedSpans
            tags   = spans.head.getAttributes
          } yield assert(tags.get(AttributeKey.booleanKey("boolean")))(equalTo(Boolean.box(true))) &&
            assert(tags.get(AttributeKey.longKey("int")))(equalTo(Long.box(1))) &&
            assert(tags.get(AttributeKey.stringKey("string")))(equalTo("foo")) &&
            assert(tags.get(AttributeKey.booleanArrayKey("booleans")))(
              equalTo(Seq(Boolean.box(true), Boolean.box(false)).asJava)
            ) &&
            assert(tags.get(AttributeKey.longArrayKey("longs")))(
              equalTo(Seq(Long.box(1L), Long.box(2L)).asJava)
            ) &&
            assert(tags.get(AttributeKey.stringArrayKey("strings")))(equalTo(Seq("foo", "bar").asJava))
        }
      },
      test("addEvent & addEventWithAttributes") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          val duration = 1000.micros

          val log = for {
            _ <- tracing.addEvent("message")
            _ <- TestClock.adjust(duration)
            _ <- tracing.addEventWithAttributes(
                   "message2",
                   Attributes.of(
                     AttributeKey.stringKey("msg"),
                     "message",
                     AttributeKey.longKey("size"),
                     Long.box(1)
                   )
                 )
          } yield ()

          for {
            _     <- log @@ span("foo")
            _     <- ZIO.unit @@ span("Child") @@ span("Root")
            spans <- getFinishedSpans
            tags   = spans.collect {
                       case span if span.getName == "foo" =>
                         span.getEvents.asScala.toList.map(le => (le.getEpochNanos, le.getName, le.getAttributes))
                     }.flatten
          } yield {
            val expected = List(
              (0L, "message", Attributes.empty()),
              (
                1000000L,
                "message2",
                Attributes.of(
                  AttributeKey.stringKey("msg"),
                  "message",
                  AttributeKey.longKey("size"),
                  Long.box(1)
                )
              )
            )
            assert(tags)(equalTo(expected))
          }
        }
      },
      test("resources") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          for {
            ref      <- Ref.make(false)
            scope    <- Scope.make
            resource  = ZIO.addFinalizer(ref.set(true))
            _        <- scope.extend(tracing.span("Resource")(resource))
            released <- ref.get
          } yield assert(released)(isFalse)
        }
      }
    ).provideLayer(tracingMockLayer)

}
