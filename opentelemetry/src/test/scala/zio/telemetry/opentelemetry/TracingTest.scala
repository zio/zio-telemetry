package zio.telemetry.opentelemetry

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat.{ Getter, Setter }
import io.opentelemetry.exporters.inmemory.InMemoryTracing
import io.opentelemetry.sdk.trace.TracerSdkProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.trace.{ SpanId, Tracer }
import zio.clock.Clock
import zio.duration._
import zio.telemetry.opentelemetry.Tracing.inject
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverterInstances._
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.test.{ assert, suite, testM, DefaultRunnableSpec }
import zio._
import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import io.grpc.Context
import io.opentelemetry.common.Attributes

object TracingTest extends DefaultRunnableSpec {

  val inMemoryTracer: UIO[(InMemoryTracing, Tracer)] = for {
    tracerProvider  <- UIO(TracerSdkProvider.builder().build())
    inMemoryTracing <- UIO(InMemoryTracing.builder().setTracerProvider(tracerProvider).build())
    // Without this cast, a runtime exception occurs on this particular line:
    // failed to access class io.opentelemetry.sdk.trace.TracerSdk from class zio.telemetry.opentelemetry.TracingTest
    // which could not be resolved, even by adding an explicit dependency on the opentelemetry-sdk package
    // (on which we implicitly depend through the opentelemetry-exporters-inmemory package)
    tracer = tracerProvider.get("TracingTest").asInstanceOf[Tracer]
  } yield (inMemoryTracing, tracer)

  val inMemoryTracerLayer: ULayer[Has[InMemoryTracing] with Has[Tracer]] = ZLayer.fromEffectMany(inMemoryTracer.map {
    case (inMemoryTracing, tracer) => Has(inMemoryTracing).add(tracer)
  })

  val tracingMockLayer: URLayer[Clock, Has[InMemoryTracing] with Tracing with Has[Tracer]] =
    (inMemoryTracerLayer ++ Clock.any) >>> (Tracing.live ++ inMemoryTracerLayer)

  def getFinishedSpans =
    ZIO
      .access[Has[InMemoryTracing]](_.get)
      .map(_.getSpanExporter.getFinishedSpanItems.asScala.toList)

  def spec =
    suite("zio opentelemetry")(
      testM("acquire/release the service") {
        for {
          _ <- Tracing.live.build
                .use_(UIO.unit)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideCustomLayer(inMemoryTracerLayer),
      suite("spans")(
        testM("childSpan") {
          for {
            _     <- UIO.unit.span("Child").span("Root")
            spans <- getFinishedSpans
            root  = spans.find(_.getName == "Root")
            child = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, SpanId](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            )
        },
        testM("scopedEffect") {
          for {
            tracer <- ZIO.service[Tracer]
            _ <- Tracing.scopedEffect {
                  val span = tracer.getCurrentSpan
                  span.addEvent("In legacy code")
                  if (Context.current() == Context.ROOT) throw new RuntimeException("Current context is root!")
                  span.addEvent("Finishing legacy code")
                }.span("Scoped")
                  .span("Root")
            spans  <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, SpanId](
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
            tracer <- ZIO.service[Tracer]
            _ <- Tracing.scopedEffectTotal {
                  val span = tracer.getCurrentSpan
                  span.addEvent("In legacy code")
                  if (Context.current() == Context.ROOT) throw new RuntimeException("Current context is root!")
                  Thread.sleep(10)
                  if (Context.current() == Context.ROOT) throw new RuntimeException("Current context is root!")
                  span.addEvent("Finishing legacy code")
                }.span("Scoped")
                  .span("Root")
            spans  <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, SpanId](
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
            tracer <- ZIO.service[Tracer]
            result <- Tracing.scopedEffectFromFuture { _ =>
                       Future.successful {
                         val span = tracer.getCurrentSpan
                         span.addEvent("In legacy code")
                         if (Context.current() == Context.ROOT)
                           throw new RuntimeException("Current context is root!")
                         span.addEvent("Finishing legacy code")
                         1
                       }
                     }.span("Scoped").span("Root")
            spans  <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(result)(equalTo(1)) &&
            assert(root)(isSome(anything)) &&
            assert(scoped)(
              isSome(
                hasField[SpanData, SpanId](
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
            root  = spans.find(_.getName == "ROOT")
            child = spans.find(_.getName == "ROOT2")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, SpanId](
                  "parent",
                  _.getParentSpanId,
                  equalTo(new SpanId(0))
                )
              )
            )
        },
        testM("inject - extract roundtrip") {
          val httpTextFormat                       = io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat
          val carrier: mutable.Map[String, String] = mutable.Map().empty

          val getter: Getter[mutable.Map[String, String]] =
            (carrier, key) => carrier.get(key).orNull

          val setter: Setter[mutable.Map[String, String]] =
            (carrier, key, value) => carrier.update(key, value)

          val injectExtract =
            inject(
              httpTextFormat,
              carrier,
              setter
            ).span("foo") *> UIO.unit
              .spanFrom(httpTextFormat, carrier, getter, "baz")
              .span("bar")

          for {
            _     <- injectExtract.span("ROOT")
            spans <- getFinishedSpans
            root  = spans.find(_.getName == "ROOT")
            foo   = spans.find(_.getName == "foo")
            bar   = spans.find(_.getName == "bar")
            baz   = spans.find(_.getName == "baz")
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
            _ <- UIO.unit
                  .setAttribute("boolean", true)
                  .setAttribute("int", 1)
                  .setAttribute("string", "foo")
                  .span("foo")
            spans <- getFinishedSpans
            tags  = spans.head.getAttributes
          } yield assert(tags.get("boolean"))(equalTo(AttributeValue.booleanAttributeValue(true))) &&
            assert(tags.get("int"))(equalTo(AttributeValue.longAttributeValue(1))) &&
            assert(tags.get("string"))(equalTo(AttributeValue.stringAttributeValue("foo")))
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
                    "msg",
                    AttributeValue.stringAttributeValue("message"),
                    "size",
                    AttributeValue.longAttributeValue(1)
                  )
                )

          for {
            _     <- log.span("foo")
            _     <- UIO.unit.span("Child").span("Root")
            spans <- getFinishedSpans
            tags = spans.collect {
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
                  "msg",
                  AttributeValue.stringAttributeValue("message"),
                  "size",
                  AttributeValue.longAttributeValue(1)
                )
              )
            )
            assert(tags)(equalTo(expected))
          }
        }
      ).provideCustomLayer(tracingMockLayer)
    )
}
