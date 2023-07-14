package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{Span, SpanId, StatusCode, Tracer}
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import zio._
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.test.Assertion._
import zio.test.{Spec, TestClock, ZIOSpecDefault, assert}
import StatusMapper.StatusMapperResult

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

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO
      .service[InMemorySpanExporter]
      .map(_.getFinishedSpanItems.asScala.toList)

  def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Tracing")(
        creationSpec,
        spansSpec,
        spanScopedSpec
      )
    )

  private val creationSpec =
    suite("creation")(
      test("live") {
        for {
          _             <- ZIO.scoped(Tracing.live.build)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provideLayer(inMemoryTracerLayer)
    )

  private val spansSpec =
    suite("spans")(
      test("span") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- ZIO.unit @@ span[Throwable, Unit]("Child") @@ span[Throwable, Unit]("Root")
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
      test("set status code for success span") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._
          val assertStatusCodeError             =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))
          val assertStatusDescriptionError      =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              equalTo("My error message. Result = success")
            )
          val assertRecordedExceptionAttributes = hasField[SpanData, List[(String, String)]](
            "exceptionAttributes",
            _.getEvents.asScala.toList
              .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
            isEmpty
          )
          val assertion                         = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper                      =
            StatusMapper.success[Throwable, String](StatusCode.ERROR, r => Option(s"My error message. Result = $r"))

          for {
            _     <-
              ZIO.succeed("success") @@ span[Throwable, String]("Child", statusMapper = statusMapper) @@ span[
                Throwable,
                String
              ]("Root")
            spans <- getFinishedSpans
            child  = spans.find(_.getName == "Child")
          } yield assert(child)(isSome(assertion))
        }
      },
      test("addLinks") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            res                        <- inMemoryTracer
            (_, tracer)                 = res
            externallyProvidedRootSpan1 = tracer.spanBuilder("external1").startSpan()
            externallyProvidedRootSpan2 = tracer.spanBuilder("external2").startSpan()
            externallyProvidedRootSpan3 = tracer.spanBuilder("external3").startSpan()
            links                       = List(externallyProvidedRootSpan1, externallyProvidedRootSpan2, externallyProvidedRootSpan3)
                                            .map(_.getSpanContext)
            _                          <- ZIO.unit @@ span[Throwable, Unit]("Child", links = links) @@ span[Throwable, Unit]("Root")
            spans                      <- getFinishedSpans
            root                        = spans.find(_.getName == "Root")
            child                       = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(
              isSome(
                hasField[SpanData, String](
                  "parentSpanId",
                  _.getParentSpanId,
                  equalTo(root.get.getSpanId)
                )
              )
            ) &&
            assert(child.toList.flatMap(_.getLinks.asScala.toList.map(_.getSpanContext.getSpanId)))(
              hasSameElements(links.map(_.getSpanId))
            )
        }
      },
      test("setError") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          val assertStatusCodeError             =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))
          val assertStatusDescriptionError      =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              containsString("java.lang.RuntimeException: some_error")
            )
          val assertRecordedExceptionAttributes = hasField[SpanData, List[(String, String)]](
            "exceptionAttributes",
            _.getEvents.asScala.toList
              .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
            hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
          )
          val assertion                         = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper                      = StatusMapper.failure[Unit](StatusCode.ERROR)

          val alwaysTrue                              = true
          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).when(alwaysTrue).unit

          for {
            _     <- (failedEffect @@ span[Throwable, Unit]("Child", statusMapper = statusMapper) @@ span[Throwable, Unit](
                       "Root",
                       statusMapper = statusMapper
                     )).ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("setError without description") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          val assertStatusCodeError             =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.UNSET))
          val assertStatusDescriptionError      =
            hasField[SpanData, String]("statusDescription", _.getStatus.getDescription, isEmptyString)
          val assertRecordedExceptionAttributes = hasField[SpanData, List[(String, String)]](
            "exceptionAttributes",
            _.getEvents.asScala.toList
              .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
            hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
          )
          val assertion                         = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper                      = StatusMapper.failure[Unit](StatusCode.UNSET)

          val alwaysTrue                              = true
          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).when(alwaysTrue).unit

          for {
            _     <- (failedEffect @@ span[Throwable, Unit]("Child", statusMapper = statusMapper) @@ span[Throwable, Unit](
                       "Root",
                       statusMapper = statusMapper
                     )).ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("root") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          import tracing.aspects._

          for {
            _     <- ZIO.unit @@ root[Throwable, Unit]("ROOT2") @@ root[Throwable, Unit]("ROOT")
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
            _                         <- ZIO.unit @@ inSpan[Throwable, Unit](externallyProvidedRootSpan, "zio-otel-child")
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
                     }.unit @@ span[Throwable, Unit]("Scoped") @@ span[Throwable, Unit]("Root")
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
                     }.unit @@ span[Throwable, Unit]("Scoped") @@ span[Throwable, Unit]("Root")
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
                      } @@ span[Throwable, Int]("Scoped") @@ span[Throwable, Int]("Root")
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

          val roundtrip =
            (for {
              _ <-
                tracing.inject(TraceContextPropagator.default, OutgoingContextCarrier.default(carrier)) @@
                  span[Throwable, Unit]("foo")
              _ <-
                ZIO.unit @@
                  extractSpan[mutable.Map[String, String], Throwable, Unit](
                    TraceContextPropagator.default,
                    IncomingContextCarrier.default(carrier),
                    "baz"
                  ) @@
                  span[Throwable, Unit]("bar")
            } yield ()) @@ span[Throwable, Unit]("ROOT")

          for {
            _     <- roundtrip
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
                       _ <- tracing.setAttribute("boolean", value = true)
                       _ <- tracing.setAttribute("int", 1)
                       _ <- tracing.setAttribute("string", "foo")
                       _ <- tracing.setAttribute("booleans", Seq(true, false))
                       _ <- tracing.setAttribute("longs", Seq(1L, 2L))
                       _ <- tracing.setAttribute("strings", Seq("foo", "bar"))
                     } yield ()) @@ span[Throwable, Unit]("foo")
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
            _     <- log @@ span[Throwable, Unit]("foo")
            _     <- ZIO.unit @@ span[Throwable, Unit]("Child") @@ span[Throwable, Unit]("Root")
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

  private val spanScopedSpec =
    suite("scoped spans")(
      test("span") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          for {
            _     <- ZIO.scoped[Any](
                       tracing.spanScoped("Root") *> ZIO.scoped[Any](
                         tracing.spanScoped("Child")
                       )
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
      test("span single scope") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          for {
            _     <- ZIO.scoped[Any](
                       for {
                         _ <- tracing.spanScoped("Root")
                         _ <- tracing.spanScoped("Child")
                       } yield ()
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
      test("setError") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          val assertStatusCodeError                   =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))
          val assertStatusDescriptionError            =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              containsString("java.lang.RuntimeException: some_error")
            )
          val assertRecordedExceptionAttributes       = hasField[SpanData, List[(String, String)]](
            "exceptionAttributes",
            _.getEvents.asScala.toList
              .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
            hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
          )
          val assertion                               = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper: StatusMapper[Any, Unit]   = StatusMapper[Any, Unit](
            { e: Any =>
              StatusMapperResult(StatusCode.ERROR, Option(e.asInstanceOf[Throwable]))
            },
            Map.empty
          )
          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).unit

          for {
            _     <- ZIO
                       .scoped[Any](
                         tracing.spanScoped("Root", statusMapper = statusMapper) *> ZIO.scoped[Any](
                           tracing.spanScoped("Child", statusMapper = statusMapper) *> failedEffect
                         )
                       )
                       .ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("setAttribute") {
        ZIO.serviceWithZIO[Tracing] { tracing =>
          for {
            _     <- ZIO.scoped[Any](for {
                       _ <- tracing.spanScoped("foo")
                       _ <- tracing.setAttribute("string", "bar")
                     } yield ())
            spans <- getFinishedSpans
            tags   = spans.head.getAttributes
          } yield assert(tags.get(AttributeKey.stringKey("string")))(equalTo("bar"))
        }
      }
    ).provideLayer(tracingMockLayer)
}
