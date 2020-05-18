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
import scala.jdk.CollectionConverters._

object TracingTest extends DefaultRunnableSpec {

  val inMemoryTracer: UIO[(InMemoryTracing, Tracer)] = for {
    tracerProvider  <- UIO(TracerSdkProvider.builder().build())
    inMemoryTracing <- UIO(InMemoryTracing.builder().setTracerProvider(tracerProvider).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (inMemoryTracing, tracer)

  val inMemoryTracerLayer: ULayer[Has[InMemoryTracing] with Has[Tracer]] = ZLayer.fromEffectMany(
    inMemoryTracer.map {
      case (inMemoryTracing, tracer) => Has(inMemoryTracing).add(tracer)
    }
  )

  val tracingMockLayer: URLayer[Clock, Has[InMemoryTracing] with Tracing] =
    (inMemoryTracerLayer ++ Clock.any) >>> (Tracing.live ++ inMemoryTracerLayer)

  def getFinishedSpans(inMemoryTracing: InMemoryTracing): List[SpanData] =
    inMemoryTracing.getSpanExporter.getFinishedSpanItems.asScala.toList

  def spec =
    suite("zio opentelemetry")(
      testM("acquire/release the service") {
        for {
          inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
          _ <- Tracing.live.build
                .use_(UIO.unit)
          finishedSpans = getFinishedSpans(inMemoryTracing)
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideCustomLayer(inMemoryTracerLayer),
      suite("spans")(
        testM("childSpan") {
          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- UIO.unit.span("Child").span("Root")
            spans           = getFinishedSpans(inMemoryTracing)
            root            = spans.find(_.getName == "Root")
            child           = spans.find(_.getName == "Child")
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
        testM("rootSpan") {
          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- UIO.unit.root("ROOT2").root("ROOT")
            spans           = getFinishedSpans(inMemoryTracing)
            root            = spans.find(_.getName == "ROOT")
            child           = spans.find(_.getName == "ROOT2")
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
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- injectExtract.span("ROOT")
            spans           = getFinishedSpans(inMemoryTracing)
            root            = spans.find(_.getName == "ROOT")
            foo             = spans.find(_.getName == "foo")
            bar             = spans.find(_.getName == "bar")
            baz             = spans.find(_.getName == "baz")
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
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _ <- UIO.unit
                  .setAttribute("boolean", true)
                  .setAttribute("int", 1)
                  .setAttribute("string", "foo")
                  .span("foo")
            spans = getFinishedSpans(inMemoryTracing)
            tags  = spans.head.getAttributes.asScala.toMap
          } yield assert(tags)(
            equalTo(
              Map(
                "boolean" -> AttributeValue.booleanAttributeValue(true),
                "int"     -> AttributeValue.longAttributeValue(1),
                "string"  -> AttributeValue.stringAttributeValue("foo")
              )
            )
          )
        },
        testM("logging") {
          val duration = 1000.micros

          val log =
            UIO.unit.addEvent("message") *>
              TestClock
                .adjust(duration)
                .addEventWithAttributes(
                  "message2",
                  Map(
                    "msg"  -> AttributeValue.stringAttributeValue("message"),
                    "size" -> AttributeValue.longAttributeValue(1)
                  )
                )

          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- log.span("foo")
            spans           = getFinishedSpans(inMemoryTracing)
            tags = spans.collect {
              case span if span.getName == "foo" =>
                span.getTimedEvents.asScala.toList.map(le =>
                  (le.getEpochNanos, le.getName, le.getAttributes.asScala.toMap)
                )
            }.flatten
          } yield {
            val expected = List(
              (0L, "message", Map.empty),
              (
                1000000L,
                "message2",
                Map(
                  "msg"  -> AttributeValue.stringAttributeValue("message"),
                  "size" -> AttributeValue.longAttributeValue(1)
                )
              )
            )
            assert(tags)(equalTo(expected))
          }
        }
      ).provideCustomLayer(tracingMockLayer)
    )
}
