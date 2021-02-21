package zio.telemetry.opentelemetry

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ Span, SpanId, Tracer }
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapSetter }
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import zio.clock.Clock
import zio.duration._
import zio.telemetry.opentelemetry.Tracing.inject
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.test.{ assert, DefaultRunnableSpec }
import zio._

import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor

import java.lang

object TracingTest extends DefaultRunnableSpec {

  val inMemoryTracer: UIO[(InMemorySpanExporter, Tracer)] = for {
    spanExporter   <- UIO(InMemorySpanExporter.create())
    spanProcessor  <- UIO(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- UIO(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[Has[InMemorySpanExporter] with Has[Tracer]] =
    ZLayer.fromEffectMany(inMemoryTracer.map { case (inMemoryTracing, tracer) =>
      Has(inMemoryTracing).add(tracer)
    })

  val tracingMockLayer: URLayer[Clock, Has[InMemorySpanExporter] with Tracing with Has[Tracer]] =
    (inMemoryTracerLayer ++ Clock.any) >>> (Tracing.live ++ inMemoryTracerLayer)

  def getFinishedSpans =
    ZIO
      .access[Has[InMemorySpanExporter]](_.get)
      .map(_.getFinishedSpanItems.asScala.toList)

  def spec =
    suite("zio opentelemetry")(
      testM("acquire/release the service") {
        for {
          _             <- Tracing.live.build
                             .use_(UIO.unit)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideCustomLayer(inMemoryTracerLayer),
      suite("spans")(
        testM("childSpan") {
          for {
            _     <- UIO.unit.span("Child").span("Root")
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
        },
        testM("scopedEffect") {
          for {
            _     <- Tracing.scopedEffect {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     }.span("Scoped")
                       .span("Root")
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
            ) && assert(tags)(
              equalTo(List("In legacy code", "Finishing legacy code"))
            )
        },
        testM("scopedEffectTotal") {
          for {
            _     <- Tracing.scopedEffectTotal {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       Thread.sleep(10)
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     }.span("Scoped")
                       .span("Root")
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
            ) && assert(tags)(
              equalTo(List("In legacy code", "Finishing legacy code"))
            )
        },
        testM("scopedEffectFromFuture") {
          for {
            result <- Tracing.scopedEffectFromFuture { _ =>
                        Future.successful {
                          val span = Span.current()
                          span.addEvent("In legacy code")
                          if (Context.current() == Context.root())
                            throw new RuntimeException("Current context is root!")
                          span.addEvent("Finishing legacy code")
                          1
                        }
                      }.span("Scoped").span("Root")
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
            ) && assert(tags)(
              equalTo(List("In legacy code", "Finishing legacy code"))
            )
        },
        testM("rootSpan") {
          for {
            _     <- UIO.unit.root("ROOT2").root("ROOT")
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
        },
        testM("inject - extract roundtrip") {
          val propagator                           = W3CTraceContextPropagator.getInstance()
          val carrier: mutable.Map[String, String] = mutable.Map().empty

          val getter: TextMapGetter[mutable.Map[String, String]] = new TextMapGetter[mutable.Map[String, String]] {
            override def keys(carrier: mutable.Map[String, String]): lang.Iterable[String] =
              carrier.keys.asJava

            override def get(carrier: mutable.Map[String, String], key: String): String =
              carrier.get(key).orNull
          }

          val setter: TextMapSetter[mutable.Map[String, String]] =
            (carrier, key, value) => carrier.update(key, value)

          val injectExtract =
            inject(
              propagator,
              carrier,
              setter
            ).span("foo") *> UIO.unit
              .spanFrom(propagator, carrier, getter, "baz")
              .span("bar")

          for {
            _     <- injectExtract.span("ROOT")
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
        },
        testM("tagging") {
          for {
            _     <- UIO.unit
                       .setAttribute("boolean", true)
                       .setAttribute("int", 1)
                       .setAttribute("string", "foo")
                       .span("foo")
            spans <- getFinishedSpans
            tags   = spans.head.getAttributes
          } yield assert(tags.get(AttributeKey.booleanKey("boolean")))(equalTo(Boolean.box(true))) &&
            assert(tags.get(AttributeKey.longKey("int")))(equalTo(Long.box(1))) &&
            assert(tags.get(AttributeKey.stringKey("string")))(equalTo("foo"))
        },
        testM("logging") {
          val duration = 1000.micros

          val log =
            UIO.unit.addEvent("message") *>
              TestClock
                .adjust(duration)
                .addEventWithAttributes(
                  "message2",
                  Attributes.of(
                    AttributeKey.stringKey("msg"),
                    "message",
                    AttributeKey.longKey("size"),
                    Long.box(1)
                  )
                )

          for {
            _     <- log.span("foo")
            _     <- UIO.unit.span("Child").span("Root")
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
      ).provideCustomLayer(tracingMockLayer)
    )
}
