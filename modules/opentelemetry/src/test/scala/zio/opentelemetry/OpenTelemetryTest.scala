package zio.opentelemetry

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.exporters.inmemory.InMemoryTracing
import io.opentelemetry.sdk.trace.TracerSdkProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.trace.{ SpanId, Tracer }
import zio.clock.Clock
import zio.duration._
import zio.opentelemetry.SpanSyntax._
import zio.opentelemetry.CurrentSpan.injectCurrentSpan
import zio.opentelemetry.attributevalue.AttributeValueConverterInstances._
import zio.test.Assertion._
import zio.test.environment.TestClock
import zio.test.{ assert, suite, testM, DefaultRunnableSpec }
import zio.{ Has, UIO, ZIO, ZLayer }

import scala.collection.JavaConverters._
import scala.collection.mutable

object OpenTelemetryTest extends DefaultRunnableSpec {

  def createMockTracer: (InMemoryTracing, Tracer) = {
    val tracerProvider: TracerSdkProvider = TracerSdkProvider.builder().build()
    val inMemoryTracing                   = InMemoryTracing.builder().setTracerProvider(tracerProvider).build()
    val tracer                            = tracerProvider.get("InMemoryTracing")

    (inMemoryTracing, tracer)
  }

  def getFinishedSpans(inMemoryTracing: InMemoryTracing): List[SpanData] =
    inMemoryTracing.getSpanExporter.getFinishedSpanItems.asScala.toList

  def createMockLayer: ZLayer[Clock, Nothing, Has[InMemoryTracing] with OpenTelemetry] = {
    val (inMemoryTracing, tracer) = createMockTracer
    ZLayer.succeed(inMemoryTracing) ++ OpenTelemetry.live(tracer)
  }

  def spec =
    suite("zio opentelemetry")(
      testM("acquire/release the service") {
        val (inMemoryTracing, tracer) = createMockTracer
        lazy val finishedSpans        = getFinishedSpans(inMemoryTracing)

        OpenTelemetry
          .live(tracer)
          .build
          .use_(UIO.unit)
          .as(assert(finishedSpans)(hasSize(equalTo(0))))
      },
      suite("spans")(
        testM("childSpan") {
          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- UIO.unit.childSpan("Child").childSpan("Root")
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
        }.provideCustomLayer(createMockLayer),
        testM("rootSpan") {
          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- UIO.unit.rootSpan("ROOT2").rootSpan("ROOT")
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
        }.provideCustomLayer(createMockLayer),
        testM("inject - extract roundtrip") {

          val httpTextFormat                       = io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat
          val carrier: mutable.Map[String, String] = mutable.Map().empty

          val getter: (mutable.Map[String, String], String) => Option[String] =
            (carrier: mutable.Map[String, String], key: String) => carrier.get(key)
          val setter = (carrier: mutable.Map[String, String], key: String, value: String) => carrier.update(key, value)
          val injectExtract =
            injectCurrentSpan(
              httpTextFormat,
              carrier,
              setter
            ).childSpan("foo") *> UIO.unit
              .spanFrom(httpTextFormat, carrier, getter, "baz")
              .childSpan("bar")

          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- injectExtract.childSpan("ROOT")
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
        }.provideCustomLayer(createMockLayer),
        testM("tagging") {
          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _ <- UIO.unit
                  .setAttribute("boolean", true)
                  .setAttribute("int", 1)
                  .setAttribute("string", "foo")
                  .childSpan("foo")
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
        }.provideCustomLayer(createMockLayer),
        testM("logging") {
          val duration = 1000.micros

          /*
           * TODO:
           * Explicit sleep has been introduced due to the change in behavior of TestClock.adjust
           * which made it affect only "wall" clock while leaving the fiber one intact. That being
           * said, this piece of code should be replaced as soon as there's a better suited combinator
           * available.
           */
          val log =
            for {
              _ <- UIO.unit.addEvent("message")
              _ <- TestClock.adjust(duration)
              _ <- ZIO
                    .sleep(duration)
                    .addEventWithAttributes(
                      "message2",
                      Map(
                        "msg"  -> AttributeValue.stringAttributeValue("message"),
                        "size" -> AttributeValue.longAttributeValue(1)
                      )
                    )
            } yield ()

          for {
            inMemoryTracing <- ZIO.access[Has[InMemoryTracing]](_.get)
            _               <- log.childSpan("foo")
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
        }.provideCustomLayer(createMockLayer)
      )
    )
}
