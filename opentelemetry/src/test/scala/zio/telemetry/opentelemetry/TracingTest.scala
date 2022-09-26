package zio.telemetry.opentelemetry

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ Span, SpanId, Tracer }
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData

import zio.test.Assertion._
import zio.test.assert
import zio.test.TestClock
import zio._

import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapSetter }
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor

import java.lang
import zio.test.ZIOSpecDefault

object TracingTest extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, Tracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with Tracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemoryTracing, tracer) =>
      ZEnvironment(inMemoryTracing).add(tracer)
    })

  val tracingMockLayer: ULayer[Tracing with InMemorySpanExporter with Tracer] =
    inMemoryTracerLayer >>> (Tracing.live ++ inMemoryTracerLayer)

  def getFinishedSpans =
    ZIO
      .service[InMemorySpanExporter]
      .map(_.getFinishedSpanItems.asScala.toList)

  def spec =
    suite("zio opentelemetry")(
      test("acquire/release the service") {
        for {
          _             <- ZIO.scoped(Tracing.live.build)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideLayer(inMemoryTracerLayer),
      suite("spans")(
        test("childSpan") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            for {
              _     <- tracing.span("Root")(
                         tracing.span("Child")(ZIO.unit)
                       )
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
        test("scopedEffect") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            for {
              _     <- tracing.span("Root")(
                         tracing.span("Scoped")(
                           tracing.scopedEffect {
                             val span = Span.current()
                             span.addEvent("In legacy code")
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             span.addEvent("Finishing legacy code")
                           }
                         )
                       )
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
            for {
              _     <- tracing.span("Root")(
                         tracing.span("Scoped")(
                           tracing.scopedEffectTotal {
                             val span = Span.current()
                             span.addEvent("In legacy code")
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             Thread.sleep(10)
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             span.addEvent("Finishing legacy code")
                           }
                         )
                       )
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
            for {
              result <- tracing.span("Root")(
                          tracing.span("Scoped")(
                            tracing.scopedEffectFromFuture { _ =>
                              Future.successful {
                                val span = Span.current()
                                span.addEvent("In legacy code")
                                if (Context.current() == Context.root())
                                  throw new RuntimeException("Current context is root!")
                                span.addEvent("Finishing legacy code")
                                1
                              }
                            }
                          )
                        )
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
        test("rootSpan") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            for {
              _     <- tracing.root("ROOT")(
                         tracing.root("ROOT2")(ZIO.unit)
                       )
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
            for {
              res                       <- inMemoryTracer
              (_, tracer)                = res
              externallyProvidedRootSpan = tracer.spanBuilder("external").startSpan()
              scope                      = externallyProvidedRootSpan.makeCurrent()
              _                         <- tracing.inSpan(externallyProvidedRootSpan, "zio-otel-child")(ZIO.unit)
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
        test("inject - extract roundtrip") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            val propagator                           = W3CTraceContextPropagator.getInstance()
            val carrier: mutable.Map[String, String] = mutable.Map().empty

            // TODO: replace with [[zio.telemetry.opentelemetry.TextMapAdapter.type]] implementation
            val getter: TextMapGetter[mutable.Map[String, String]] = new TextMapGetter[mutable.Map[String, String]] {
              override def keys(carrier: mutable.Map[String, String]): lang.Iterable[String] =
                carrier.keys.asJava

              override def get(carrier: mutable.Map[String, String], key: String): String =
                carrier.get(key).orNull
            }

            val setter: TextMapSetter[mutable.Map[String, String]] =
              (carrier, key, value) => carrier.update(key, value)

            for {
              _     <- tracing.span("ROOT")(
                         for {
                           _ <- tracing.span("foo")(
                                  tracing.inject(propagator, carrier, setter)
                                )
                           _ <- tracing.span("bar")(
                                  tracing.spanFrom(propagator, carrier, getter, "baz")(ZIO.unit)
                                )
                         } yield ()
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
              assert(bar.get.getParentSpanId)(equalTo(root.get.getSpanId)) &&
              assert(baz.get.getParentSpanId)(equalTo(foo.get.getSpanId))
          }
        },
        test("tagging") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            for {
              _     <- tracing.span("foo")(
                         for {
                           _ <- tracing.setAttribute("boolean", true)
                           _ <- tracing.setAttribute("int", 1)
                           _ <- tracing.setAttribute("string", "foo")
                           _ <- tracing.setAttribute("booleans", Seq(true, false))
                           _ <- tracing.setAttribute("longs", Seq(1L, 2L))
                           _ <- tracing.setAttribute("strings", Seq("foo", "bar"))
                         } yield ()
                       )
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
        test("logging") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
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
              _     <- tracing.span("foo")(log)
              _     <- tracing.span("Root")(
                         tracing.span("Child")(ZIO.unit)
                       )
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
        test("baggaging") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            for {
              _         <- tracing.setBaggage("some", "thing")
              baggage   <- tracing.getCurrentBaggage
              entryValue = Option(baggage.getEntryValue("some"))
            } yield assert(entryValue)(equalTo(Some("thing")))
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
    )
}
