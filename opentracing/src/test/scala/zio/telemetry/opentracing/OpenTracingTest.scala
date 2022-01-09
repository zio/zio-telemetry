package zio.telemetry.opentracing

import io.opentracing.mock.{ MockSpan, MockTracer }
import io.opentracing.propagation.{ BinaryAdapters, Format, TextMapAdapter }
import zio._
import zio.Clock

import zio.test._
import zio.test.Assertion._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import java.nio.ByteBuffer
import zio.test.ZIOSpecDefault
object OpenTracingTest extends ZIOSpecDefault {

  type HasMockTracer = MockTracer

  val mockTracer: Layer[Nothing, HasMockTracer] =
    ZLayer.fromEffect(UIO(new MockTracer))

  val testService: URLayer[HasMockTracer with Clock, OpenTracing] =
    ZLayer.fromServiceManaged((tracer: MockTracer) => OpenTracing.managed(tracer, "ROOT"))

  val customLayer = mockTracer ++ ((mockTracer ++ Clock.any) >>> testService)

  def spec =
    suite("zio opentracing")(
      test("managedService") {
        val tracer = new MockTracer

        OpenTracing
          .live(tracer, "ROOT")
          .build
          .useDiscard(UIO.unit)
          .as(
            assert(tracer.finishedSpans.asScala)(hasSize(equalTo(1))) && assert(tracer.finishedSpans().get(0))(
              hasField[MockSpan, String](
                "operationName",
                _.operationName(),
                equalTo("ROOT")
              ) &&
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(0L)
                )
            )
          )
      },
      suite("spans")(
        test("childSpan") {
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- UIO.unit.span("Child").span("ROOT")
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "Child")
            assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(root.get.context().spanId())
                )
              )
            )
          }
        },
        test("rootSpan") {
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- UIO.unit.root("ROOT2").root("ROOT")
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "ROOT2")
            assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(0L)
                )
              )
            )
          }
        },
        test("spanFrom behaves like root if extract returns null") {
          val tm = new TextMapAdapter(mutable.Map.empty.asJava)
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- UIO.unit.spanFrom(Format.Builtin.TEXT_MAP, tm, "spanFrom")
          } yield {
            val spans    = tracer.finishedSpans.asScala
            val spanFrom = spans.find(_.operationName() == "spanFrom")
            assert(spanFrom)(
              isSome(
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(0L)
                )
              )
            )
          }
        },
        test("spanFrom is a no-op if extract throws") {
          val byteBuffer = ByteBuffer.wrap("corrupted binary".toCharArray.map(x => x.toByte))
          val tm         = BinaryAdapters.extractionCarrier(byteBuffer)
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- UIO.unit.spanFrom(Format.Builtin.BINARY_EXTRACT, tm, "spanFrom")
          } yield {
            val spans    = tracer.finishedSpans.asScala
            val spanFrom = spans.find(_.operationName() == "spanFrom")
            assert(spanFrom)(isNone)
          }
        },
        test("inject - extract roundtrip") {
          val tm            = new TextMapAdapter(mutable.Map.empty.asJava)
          val injectExtract = OpenTracing.inject(Format.Builtin.TEXT_MAP, tm).span("foo") *>
            OpenTracing
              .spanFrom(Format.Builtin.TEXT_MAP, tm, UIO.unit, "baz")
              .span("bar")
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- injectExtract.span("ROOT")
          } yield {
            val spans = tracer.finishedSpans().asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val foo   = spans.find(_.operationName() == "foo")
            val bar   = spans.find(_.operationName() == "bar")
            val baz   = spans.find(_.operationName() == "baz")
            assert(root)(isSome(anything)) &&
            assert(foo)(isSome(anything)) &&
            assert(bar)(isSome(anything)) &&
            assert(baz)(isSome(anything)) &&
            assert(foo.get.parentId())(equalTo(root.get.context().spanId())) &&
            assert(bar.get.parentId())(equalTo(root.get.context().spanId())) &&
            assert(baz.get.parentId())(equalTo(foo.get.context().spanId()))
          }
        },
        test("tagging") {
          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- UIO.unit
                        .tag("boolean", true)
                        .tag("int", 1)
                        .tag("string", "foo")
                        .span("foo")
          } yield {
            val tags     = tracer.finishedSpans().asScala.head.tags.asScala.toMap
            val expected = Map[String, Any]("boolean" -> true, "int" -> 1, "string" -> "foo")
            assert(tags)(equalTo(expected))
          }
        },
        test("logging") {
          val duration = 1000.micros

          val log =
            UIO.unit.log("message") *>
              TestClock.adjust(duration).log(Map("msg" -> "message", "size" -> 1))

          for {
            tracer <- ZIO.access[HasMockTracer](_.get)
            _      <- log.span("foo")
          } yield {
            val tags =
              tracer
                .finishedSpans()
                .asScala
                .collect {
                  case span if span.operationName == "foo" =>
                    span.logEntries().asScala.map(le => le.timestampMicros -> le.fields.asScala.toMap)
                }
                .flatten
                .toList

            val expected = List(
              0L    -> Map("event" -> "message"),
              1000L -> Map[String, Any]("msg" -> "message", "size" -> 1)
            )
            assert(tags)(equalTo(expected))
          }
        },
        test("baggage") {
          for {
            _      <- OpenTracing.setBaggageItem("foo", "bar")
            _      <- OpenTracing.setBaggageItem("bar", "baz")
            fooBag <- OpenTracing.getBaggageItem("foo")
            barBag <- OpenTracing.getBaggageItem("bar")
          } yield assert(fooBag)(isSome(equalTo("bar"))) &&
            assert(barBag)(isSome(equalTo("baz")))
        }
      ).provideCustomLayer(customLayer)
    )
}
