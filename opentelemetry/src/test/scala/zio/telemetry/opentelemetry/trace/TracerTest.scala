package zio.telemetry.opentelemetry.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{Span, SpanId, StatusCode, Tracer => JTracer}
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.test.Assertion._
import zio.test.{Spec, TestClock, ZIOSpecDefault, assert}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

object TracerTest extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, JTracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with JTracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

  def ctxStorageLayer: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def tracerMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracer with InMemorySpanExporter with Tracer] =
    inMemoryTracerLayer >>> (tracerLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracerLiveLayer(logAnnotated: Boolean = false): URLayer[JTracer with ContextStorage, Tracer] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        jtracer    <- ZIO.service[JTracer]
        tracer     <- zio.telemetry.opentelemetry.trace.Tracer.scoped(jtracer, ctxStorage, logAnnotated)
      } yield tracer
    }

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Tracing")(
        creationSpec,
        spansSpec,
        spanScopedSpec,
        spanWithLogAnnotationsSpec
      )
    )

  private val creationSpec =
    suite("creation")(
      test("live") {
        for {
          _             <- ZIO.scoped(tracerLiveLayer().build)
          finishedSpans <- getFinishedSpans
        } yield assert(finishedSpans)(hasSize(equalTo(0)))
      }.provide(inMemoryTracerLayer, ctxStorageLayer)
    )

  private val spansSpec =
    suite("spans")(
      test("root") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

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
      test("span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

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
      test("inSpan") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- tracer.scopedEffect {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     }.unit @@ span("Scoped") @@ span("Root")
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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- tracer.scopedEffectTotal {
                       val span = Span.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       Thread.sleep(10)
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     }.unit @@ span("Scoped") @@ span("Root")
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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            result <- tracer.scopedEffectFromFuture { _ =>
                        Future.successful {
                          val span = Span.current()
                          span.addEvent("In legacy code")
                          if (Context.current() == Context.root())
                            throw new RuntimeException("Current context is root!")
                          span.addEvent("Finishing legacy code")
                          1
                        }
                      } @@ span("Scoped") @@ span("Root")
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
      test("setAttribute") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- (for {
                       _ <- tracer.setAttribute("boolean", value = true)
                       _ <- tracer.setAttribute("int", 1)
                       _ <- tracer.setAttribute("string", "foo")
                       _ <- tracer.setAttribute("booleans", Seq(true, false))
                       _ <- tracer.setAttribute("longs", Seq(1L, 2L))
                       _ <- tracer.setAttribute("strings", Seq("foo", "bar"))
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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          val duration = 1000.micros

          val log = for {
            _ <- tracer.addEvent("message")
            _ <- TestClock.adjust(duration)
            _ <- tracer.addEventWithAttributes(
                   "message2",
                   Attributes(Attribute.string("msg", "message"), Attribute.long("size", 1L))
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
              (0L, "message", Attributes.empty),
              (
                1000000L,
                "message2",
                Attributes(Attribute.string("msg", "message"), Attribute.long("size", 1L))
              )
            )
            assert(tags)(equalTo(expected))
          }
        }
      },
      test("addLinks") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            res                        <- inMemoryTracer
            (_, tracer)                 = res
            externallyProvidedRootSpan1 = tracer.spanBuilder("external1").startSpan()
            externallyProvidedRootSpan2 = tracer.spanBuilder("external2").startSpan()
            externallyProvidedRootSpan3 = tracer.spanBuilder("external3").startSpan()
            links                       = List(externallyProvidedRootSpan1, externallyProvidedRootSpan2, externallyProvidedRootSpan3)
                                            .map(_.getSpanContext)
            _                          <- ZIO.unit @@ span("Child", links = links) @@ span("Root")
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
      test("resources") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            ref      <- Ref.make(false)
            scope    <- Scope.make
            resource  = ZIO.addFinalizer(ref.set(true))
            _        <- scope.extend[Any](tracer.span("Resource")(resource))
            released <- ref.get
          } yield assert(released)(isFalse)
        }
      },
      test("status mapper for successful span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          val assertStatusCodeError =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))

          val assertStatusDescriptionError =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              equalTo("My error message. Result = success")
            )

          val assertRecordedExceptionAttributes =
            hasField[SpanData, List[(String, String)]](
              "exceptionAttributes",
              _.getEvents.asScala.toList
                .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
              isEmpty
            )

          val assertion = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError

          val statusMapper =
            zio.telemetry.opentelemetry.trace.StatusMapper.success[String](_ => StatusCode.ERROR)(r =>
              Option(s"My error message. Result = $r")
            )

          for {
            _     <-
              ZIO.succeed("success") @@ span("Child", statusMapper = statusMapper) @@ span("Root")
            spans <- getFinishedSpans
            child  = spans.find(_.getName == "Child")
          } yield assert(child)(isSome(assertion))
        }
      },
      test("status mapper for failed span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          val assertStatusCodeError =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))

          val assertStatusDescriptionError =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              containsString("java.lang.RuntimeException: some_error")
            )

          val assertRecordedExceptionAttributes =
            hasField[SpanData, List[(String, String)]](
              "exceptionAttributes",
              _.getEvents.asScala.toList
                .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
              hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
            )

          val assertion    = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper = zio.telemetry.opentelemetry.trace.StatusMapper.failureThrowable(_ => StatusCode.ERROR)

          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).when(true).unit

          for {
            _     <- (
                       failedEffect @@
                         span("Child", statusMapper = statusMapper) @@
                         span("Root", statusMapper = statusMapper)
                     ).ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("status mapper for failed span when error type is not Throwable") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          val assertStatusCodeError =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))

          val assertStatusDescriptionError =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              containsString("Error(some_error)")
            )

          val assertRecordedExceptionAttributes =
            hasField[SpanData, List[(String, String)]](
              "exceptionAttributes",
              _.getEvents.asScala.toList
                .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
              hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
            )

          val assertion    = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper =
            zio.telemetry.opentelemetry.trace.StatusMapper.failure[Error](_ => StatusCode.ERROR)(e =>
              Option(new RuntimeException(e.msg))
            )

          final case class Error(msg: String)
          val failedEffect: ZIO[Any, Error, Unit] =
            ZIO.fail(Error("some_error")).when(true).unit

          for {
            _     <- (
                       failedEffect @@
                         span("Child", statusMapper = statusMapper) @@
                         span("Root", statusMapper = statusMapper)
                     ).ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("status mapper without description for failed span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          val assertStatusCodeUnset =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.UNSET))

          val assertStatusDescriptionEmpty =
            hasField[SpanData, String]("statusDescription", _.getStatus.getDescription, isEmptyString)

          val assertRecordedExceptionAttributes =
            hasField[SpanData, List[(String, String)]](
              "exceptionAttributes",
              _.getEvents.asScala.toList
                .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
              hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
            )

          val assertion    = assertStatusCodeUnset && assertRecordedExceptionAttributes && assertStatusDescriptionEmpty
          val statusMapper = zio.telemetry.opentelemetry.trace.StatusMapper.failureThrowable(_ => StatusCode.UNSET)

          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).when(true).unit

          for {
            _     <- (
                       failedEffect @@
                         span("Child", statusMapper = statusMapper) @@
                         span("Root", statusMapper = statusMapper)
                     ).ignore
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            child  = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(assertion)) && assert(child)(isSome(assertion))
        }
      },
      test("combine status mappers") {
        val assertErrorStatusCodeUnset =
          hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.UNSET))

        val assertSuccessStatusCodeOk =
          hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.OK))

        val assertStatusDescriptionEmpty =
          hasField[SpanData, String]("statusDescription", _.getStatus.getDescription, isEmptyString)

        val failureAssertion = assertErrorStatusCodeUnset && assertStatusDescriptionEmpty
        val successAssertion = assertSuccessStatusCodeOk && assertStatusDescriptionEmpty

        val failureMapper = zio.telemetry.opentelemetry.trace.StatusMapper.failureThrowable(_ => StatusCode.UNSET)
        val successMapper =
          zio.telemetry.opentelemetry.trace.StatusMapper.successNoDescription[Unit](_ => StatusCode.OK)
        val statusMapper  = zio.telemetry.opentelemetry.trace.StatusMapper.both(failureMapper, successMapper)

        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- (
                       ZIO.fail[Throwable](new RuntimeException("error")).unit @@
                         span("KO", statusMapper = statusMapper)
                     ).ignore
            _     <- ZIO.succeed(()) @@ span("OK", statusMapper = statusMapper)
            spans <- getFinishedSpans
            ko     = spans.find(_.getName == "KO")
            ok     = spans.find(_.getName == "OK")
          } yield assert(ko)(isSome(failureAssertion)) && assert(ok)(isSome(successAssertion))
        }
      }
    ).provide(tracerMockLayer(), ctxStorageLayer)

  private val spanScopedSpec =
    suite("scoped spans")(
      test("span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            _     <- ZIO.scoped[Any](
                       tracer.spanScoped("Root") *> ZIO.scoped[Any](tracer.spanScoped("Child"))
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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            _     <- ZIO.scoped[Any](
                       for {
                         _ <- tracer.spanScoped("Root")
                         _ <- tracer.spanScoped("Child")
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
      test("status mapper for failed span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertStatusCodeError =
            hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, equalTo(StatusCode.ERROR))

          val assertStatusDescriptionError =
            hasField[SpanData, String](
              "statusDescription",
              _.getStatus.getDescription,
              containsString("java.lang.RuntimeException: some_error")
            )

          val assertRecordedExceptionAttributes =
            hasField[SpanData, List[(String, String)]](
              "exceptionAttributes",
              _.getEvents.asScala.toList
                .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
              hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
            )

          val assertion    = assertStatusCodeError && assertRecordedExceptionAttributes && assertStatusDescriptionError
          val statusMapper = zio.telemetry.opentelemetry.trace.StatusMapper.failure[Any](_ => StatusCode.ERROR)(e =>
            Option(e.asInstanceOf[Throwable])
          )

          val failedEffect: ZIO[Any, Throwable, Unit] =
            ZIO.fail(new RuntimeException("some_error")).unit

          for {
            _     <- ZIO
                       .scoped[Any](
                         tracer.spanScoped("Root", statusMapper = statusMapper) *>
                           ZIO.scoped[Any](
                             tracer.spanScoped("Child", statusMapper = statusMapper) *> failedEffect
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
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            _     <- ZIO.scoped[Any](for {
                       _ <- tracer.spanScoped("foo")
                       _ <- tracer.setAttribute("string", "bar")
                     } yield ())
            spans <- getFinishedSpans
            tags   = spans.head.getAttributes
          } yield assert(tags.get(AttributeKey.stringKey("string")))(equalTo("bar"))
        }
      }
    ).provide(tracerMockLayer(), ctxStorageLayer)

  private val spanWithLogAnnotationsSpec = suite("spans with log annotations")(
    test("add log annotations") {
      ZIO.serviceWithZIO[Tracer] { tracer =>
        import tracer.aspects._

        for {
          _     <- ZIO.logAnnotate("log-attribute", "foo") {
                     ZIO.unit @@ span("Root", attributes = Attributes(Attribute.string("root-attribute", "bar")))
                   }
          spans <- getFinishedSpans
          tags   = spans.head.getAttributes
        } yield assert(tags.get(AttributeKey.stringKey("root-attribute")))(equalTo("bar")) &&
          assert(tags.get(AttributeKey.stringKey("log-attribute")))(equalTo("foo"))
      }
    }.provide(tracerMockLayer(true), ctxStorageLayer),
    test("span attributes override log annotated") {
      ZIO.serviceWithZIO[Tracer] { tracer =>
        import tracer.aspects._

        for {
          _     <- ZIO.logAnnotate("some-attribute", "foo") {
                     ZIO.unit @@ span("Root", attributes = Attributes(Attribute.string("some-attribute", "bar")))
                   }
          spans <- getFinishedSpans
          tags   = spans.head.getAttributes
        } yield assert(tags.get(AttributeKey.stringKey("some-attribute")))(equalTo("bar"))
      }
    }.provide(tracerMockLayer(true), ctxStorageLayer),
    test("not add log annotations") {
      ZIO.serviceWithZIO[Tracer] { tracer =>
        import tracer.aspects._

        for {
          _     <- ZIO.logAnnotate("log-attribute", "foo") {
                     ZIO.unit @@ span("Root", attributes = Attributes(Attribute.string("root-attribute", "bar")))
                   }
          spans <- getFinishedSpans
          tags   = spans.head.getAttributes
        } yield assert(tags.get(AttributeKey.stringKey("root-attribute")))(equalTo("bar")) &&
          assert(Option(tags.get(AttributeKey.stringKey("log-attribute"))))(isNone)
      }
    }.provide(tracerMockLayer(), ctxStorageLayer)
  )
}
