package zio.opentracing

import io.opentracing.mock.MockSpan
import io.opentracing.mock.MockTracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import zio._
import zio.clock.Clock
import zio.duration._
import zio.test._
import zio.test.Assertion._
import zio.test.environment.TestClock

object OpenTracingTest extends DefaultRunnableSpec {

  type HasMockTracer = Has[MockTracer]

  val mockTracer: Layer[Nothing, HasMockTracer] =
    ZLayer.fromEffect(UIO(new MockTracer))

  val testService: URLayer[HasMockTracer with Clock, OpenTracing] =
    ZLayer.fromServiceManaged(tracer => OpenTracing.managed(tracer, "ROOT"))

  val customLayer = mockTracer ++ ((mockTracer ++ Clock.any) >>> testService)

  def spec =
    suite("zio opentracing")(
      testM("managedService") {
        val tracer = new MockTracer
        OpenTracing
          .managed(tracer, "ROOT")
          .use_(UIO.unit)
          .map(_ =>
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
        testM("childSpan") {
          for {
            env    <- ZIO.environment[HasMockTracer]
            tracer = env.get[MockTracer]
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
        testM("rootSpan") {
          for {
            env    <- ZIO.environment[HasMockTracer]
            tracer = env.get[MockTracer]
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
        testM("inject - extract roundtrip") {
          val tm = new TextMapAdapter(mutable.Map.empty.asJava)
          val injectExtract = OpenTracing.inject(Format.Builtin.TEXT_MAP, tm).span("foo") *>
            OpenTracing
              .spanFrom(Format.Builtin.TEXT_MAP, tm, UIO.unit, "baz")
              .span("bar")
          for {
            env    <- ZIO.environment[HasMockTracer]
            tracer = env.get[MockTracer]
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
        testM("tagging") {
          for {
            env    <- ZIO.environment[HasMockTracer]
            tracer = env.get[MockTracer]
            _ <- UIO.unit
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
              _ <- UIO.unit.log("message")
              _ <- TestClock.adjust(duration)
              _ <- ZIO.sleep(duration).log(Map("msg" -> "message", "size" -> 1))
            } yield ()

          for {
            env    <- ZIO.environment[HasMockTracer]
            tracer = env.get[MockTracer]
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
              0L    -> Map("event"            -> "message"),
              1000L -> Map[String, Any]("msg" -> "message", "size" -> 1)
            )
            assert(tags)(equalTo(expected))
          }
        },
        testM("baggage") {
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
