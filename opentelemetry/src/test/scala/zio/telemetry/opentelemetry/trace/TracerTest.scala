package zio.telemetry.opentelemetry.trace

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{Span => JSpan, SpanId, StatusCode, Tracer => JTracer}
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.test.Assertion._
import zio.test.{Assertion, Spec, TestClock, ZIOSpecDefault, assert}

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
  ): URLayer[ContextStorage, Tracer with InMemorySpanExporter with JTracer] =
    inMemoryTracerLayer >>> (tracerLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracerLiveLayer(logAnnotated: Boolean = false): URLayer[JTracer with ContextStorage, Tracer] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        jtracer    <- ZIO.service[JTracer]
        tracer      = Tracer.make(jtracer, ctxStorage, logAnnotated)
      } yield tracer
    }

  def getFinishedSpans: ZIO[InMemorySpanExporter, Nothing, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  def assertSpanStatusCode(assertion: Assertion[StatusCode]): Assertion[SpanData] =
    hasField[SpanData, StatusCode]("statusCode", _.getStatus.getStatusCode, assertion)

  def assertSpanDescription(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String]("statusDescription", _.getStatus.getDescription, assertion)

  def assertSpanException(assertion: Assertion[List[(String, String)]]): Assertion[SpanData] =
    hasField[SpanData, List[(String, String)]](
      "exceptionAttributes",
      _.getEvents.asScala.toList
        .flatMap(_.getAttributes.asMap().asScala.toList.map(x => x._1.getKey -> x._2.toString)),
      assertion
    )

  def assertSpanParentId(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String](
      "parentSpanId",
      _.getParentSpanId,
      assertion
    )

  def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Tracer")(
        creationSpec,
        spansSpec,
        spanScopedSpec,
        spanOperationsSpec,
        statusMapperSpec,
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

          val assertParentId = assertSpanParentId(equalTo(SpanId.getInvalid))

          for {
            _     <- ZIO.unit @@ root("ROOT2") @@ root("ROOT")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "ROOT")
            child  = spans.find(_.getName == "ROOT2")
          } yield assert(root)(isSome(anything)) && assert(child)(isSome(assertParentId))
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
            assert(child)(isSome(assertSpanParentId(equalTo(root.get.getSpanId))))
        }
      },
      test("continueSpan") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            res         <- inMemoryTracer
            (_, jtracer) = res
            span         = Span.make(jtracer.spanBuilder("external").startSpan())
            scope        = span.unsafe.asJava.makeCurrent()
            _           <- ZIO.unit @@ tracer.aspects.continueSpan(span, "zio-otel-child")
            _           <- span.end
            _            = scope.close()
            spans       <- getFinishedSpans
            child        = spans.find(_.getName == "zio-otel-child")
          } yield assert(child)(isSome(assertSpanParentId(equalTo(span.context.getSpanId))))
        }
      },
      test("unmanagedScope") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- tracer.unmanagedScope {
                       val span = JSpan.current()
                       span.addEvent("In legacy code")
                       if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                       span.addEvent("Finishing legacy code")
                     }.unit @@ span("Scoped") @@ span("Root")
            spans <- getFinishedSpans
            root   = spans.find(_.getName == "Root")
            scoped = spans.find(_.getName == "Scoped")
            tags   = scoped.get.getEvents.asScala.toList.map(_.getName)
          } yield assert(root)(isSome(anything)) &&
            assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.getSpanId)))) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
        }
      },
      test("unmanagedScopeTotal") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            _     <- tracer.unmanagedScopeTotal {
                       val span = JSpan.current()
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
            assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.getSpanId)))) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
        }
      },
      test("unmanagedScopeFuture") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          import tracer.aspects._

          for {
            result <- tracer.unmanagedScopeFuture { _ =>
                        Future.successful {
                          val span = JSpan.current()
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
            assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.getSpanId)))) &&
            assert(tags)(equalTo(List("In legacy code", "Finishing legacy code")))
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
            assert(child)(isSome(assertSpanParentId(equalTo(root.get.getSpanId))))
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
            assert(child)(isSome(assertSpanParentId(equalTo(root.get.getSpanId))))
        }
      },
      test("status mapper for failed span") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertDescription = assertSpanDescription(equalTo(""))
          val assertException   = assertSpanException(
            hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
          )

          val assertError  =
            assertStatusCode && assertException && assertDescription
          val statusMapper =
            StatusMapper.failureThrowable(_ => StatusCode.ERROR)

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
          } yield assert(root)(isSome(assertError)) && assert(child)(isSome(assertError))
        }
      },
      test("setAttribute") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            _     <- ZIO.scoped[Any](for {
                       span <- tracer.spanScoped("foo")
                       _    <- span.setAttribute("string", "bar")
                     } yield ())
            spans <- getFinishedSpans
            tags   = spans.head.getAttributes
          } yield assert(tags.get(AttributeKey.stringKey("string")))(equalTo("bar"))
        }
      }
    ).provide(tracerMockLayer(), ctxStorageLayer)

  private val spanOperationsSpec =
    suite("span operations")(
      test("setAttribute") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          for {
            _     <- tracer.span("foo") { span =>
                       for {
                         _ <- span.setAttribute("boolean", value = true)
                         _ <- span.setAttribute("int", 1)
                         _ <- span.setAttribute("string", "foo")
                         _ <- span.setAttribute("booleans", Seq(true, false))
                         _ <- span.setAttribute("longs", Seq(1L, 2L))
                         _ <- span.setAttribute("strings", Seq("foo", "bar"))
                       } yield ()
                     }
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
          val duration = 1000.micros

          for {
            _     <- tracer.span("foo") { span =>
                       for {
                         _ <- span.addEvent("message")
                         _ <- TestClock.adjust(duration)
                         _ <- span.addEventWithAttributes(
                                "message2",
                                Attributes(Attribute.string("msg", "message"), Attribute.long("size", 1L))
                              )
                       } yield ()
                     }
            _     <- ZIO.unit @@ tracer.aspects.span("Child") @@ tracer.aspects.span("Root")
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
            links                       = List(
                                            externallyProvidedRootSpan1,
                                            externallyProvidedRootSpan2,
                                            externallyProvidedRootSpan3
                                          ).map(_.getSpanContext)
            _                          <- ZIO.unit @@ span("Child", links = links) @@ span("Root")
            spans                      <- getFinishedSpans
            root                        = spans.find(_.getName == "Root")
            child                       = spans.find(_.getName == "Child")
          } yield assert(root)(isSome(anything)) &&
            assert(child)(isSome(assertSpanParentId(equalTo(root.get.getSpanId)))) &&
            assert(child.toList.flatMap(_.getLinks.asScala.toList.map(_.getSpanContext.getSpanId)))(
              hasSameElements(links.map(_.getSpanId))
            )
        }
      }
    ).provide(tracerMockLayer(), ctxStorageLayer)

  private val statusMapperSpec =
    suite("status mapper")(
      test("empty") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertEmptyOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
          val assertEmptyOkDescription = assertSpanDescription(equalTo(""))

          val assertEmptyFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
          val assertEmptyFailedDescription = assertSpanDescription(equalTo(""))

          val assertManuallySetOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
          val assertManuallySetOkDescription = assertSpanDescription(equalTo(""))

          val assertManuallySetErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertManuallySetErrorDescription = assertSpanDescription(equalTo("Error"))

          val assertEmptyOk          =
            assertEmptyOkStatusCode && assertEmptyOkDescription
          val assertEmptyFailed      =
            assertEmptyFailedStatusCode && assertEmptyFailedDescription
          val assertManuallySetOk    =
            assertManuallySetOkStatusCode && assertManuallySetOkDescription
          val assertManuallySetError =
            assertManuallySetErrorStatusCode && assertManuallySetErrorDescription

          for {
            _               <- ZIO.unit @@ tracer.aspects.span("empty-ok", statusMapper = StatusMapper.empty)
            _               <- (
                                 ZIO.fail(new RuntimeException("Error"))
                                   @@ tracer.aspects.span("empty-failed", statusMapper = StatusMapper.empty)
                               ).either
            _               <- tracer.span("manually-set-ok", statusMapper = StatusMapper.empty) { span =>
                                 span.setStatus(StatusCode.OK, "OK")
                               }
            _               <- tracer.span("manually-set-error", statusMapper = StatusMapper.empty) { span =>
                                 span.setStatus(StatusCode.ERROR, "Error")
                               }
            spans           <- getFinishedSpans
            emptyOk          = spans.find(_.getName == "empty-ok")
            emptyFailed      = spans.find(_.getName == "empty-failed")
            manuallySetOk    = spans.find(_.getName == "manually-set-ok")
            manuallySetError = spans.find(_.getName == "manually-set-error")
          } yield assert(emptyOk)(isSome(assertEmptyOk)) &&
            assert(emptyFailed)(isSome(assertEmptyFailed)) &&
            assert(manuallySetOk)(isSome(assertManuallySetOk)) &&
            assert(manuallySetError)(isSome(assertManuallySetError))
        }
      },
      test("default") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
          val assertOkDescription = assertSpanDescription(equalTo(""))

          val assertFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertFailedDescription = assertSpanDescription(equalTo(""))

          val assertDefaultOk     =
            assertOkStatusCode && assertOkDescription
          val assertDefaultFailed =
            assertFailedStatusCode && assertFailedDescription

          for {
            _            <- ZIO.unit @@ tracer.aspects.span("default-ok", statusMapper = StatusMapper.default)
            _            <- (
                              ZIO.fail(new RuntimeException("Error")) @@
                                tracer.aspects.span("default-failed", statusMapper = StatusMapper.default)
                            ).either
            spans        <- getFinishedSpans
            defaultOk     = spans.find(_.getName == "default-ok")
            defaultFailed = spans.find(_.getName == "default-failed")
          } yield assert(defaultOk)(isSome(assertDefaultOk)) && assert(defaultFailed)(isSome(assertDefaultFailed))
        }
      },
      test("both") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertDefaultOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
          val assertDefaultOkDescription = assertSpanDescription(equalTo(""))

          val assertDefaultFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertDefaultFailedDescription = assertSpanDescription(equalTo(""))

          val assertionNotDefaultOk     =
            (assertDefaultOkStatusCode && assertDefaultOkDescription).negate
          val assertionNotDefaultFailed =
            (assertDefaultFailedStatusCode && assertDefaultFailedDescription).negate

          val statusMapper =
            StatusMapper.both(
              StatusMapper.success[Unit](_ => StatusCode.OK)(_ => Some("OK")),
              StatusMapper.failureThrowable(_ => StatusCode.OK)
            )

          for {
            _            <- ZIO.unit @@ tracer.aspects.span("default-ok", statusMapper = statusMapper)
            _            <- (
                              ZIO.fail(new RuntimeException("Error")) @@
                                tracer.aspects.span("default-failed", statusMapper = statusMapper)
                            ).either
            spans        <- getFinishedSpans
            defaultOk     = spans.find(_.getName == "default-ok")
            defaultFailed = spans.find(_.getName == "default-failed")
          } yield assert(defaultOk)(isSome(assertionNotDefaultOk)) &&
            assert(defaultFailed)(isSome(assertionNotDefaultFailed))
        }
      },
      test("success & successNoDescription") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
          val assertOkDescription = assertSpanDescription(equalTo(""))

          val assertErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertErrorDescription = assertSpanDescription(equalTo("Error"))

          val assertOk              =
            assertOkStatusCode && assertOkDescription
          val assertOkNoDescription =
            assertOkStatusCode && assertOkDescription
          val assertError           =
            assertErrorStatusCode && assertErrorDescription

          for {
            _              <-
              ZIO.unit @@
                tracer.aspects
                  .span("ok", statusMapper = StatusMapper.success[Unit](_ => StatusCode.OK)(_ => Some("OK")))
            _              <-
              ZIO.unit @@
                tracer.aspects
                  .span("ok-no-description", statusMapper = StatusMapper.successNoDescription[Unit](_ => StatusCode.OK))
            _              <-
              ZIO.unit @@
                tracer.aspects
                  .span("error", statusMapper = StatusMapper.success[Unit](_ => StatusCode.ERROR)(_ => Some("Error")))
            spans          <- getFinishedSpans
            ok              = spans.find(_.getName == "ok")
            okNoDescription = spans.find(_.getName == "ok-no-description")
            error           = spans.find(_.getName == "error")
          } yield assert(ok)(isSome(assertOk)) &&
            assert(okNoDescription)(isSome(assertOkNoDescription)) &&
            assert(error)(isSome(assertError))
        }
      },
      test("failure && failureNoException && failureNoDescription") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertOkStatusCode     = assertSpanStatusCode(equalTo(StatusCode.OK))
          val assertOkDescription    = assertSpanDescription(equalTo(""))
          val assertOkExceptionEmpty = assertSpanException(isEmpty)
          val assertOkExceptionIsSet = assertSpanException(
            hasSubset(List("exception.message" -> "OK", "exception.type" -> "java.lang.RuntimeException"))
          )

          val assertErrorStatusCode       = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertErrorDescriptionEmpty = assertSpanDescription(equalTo(""))
          val assertErrorDescription      = assertSpanDescription(equalTo("Error"))
          val assertErrorExceptionEmpty   = assertSpanException(isEmpty)
          val assertErrorException        = assertSpanException(
            hasSubset(List("exception.message" -> "Error", "exception.type" -> "java.lang.RuntimeException"))
          )

          val assertOkNoException               =
            assertOkStatusCode && assertOkDescription && assertOkExceptionEmpty
          val assertOkNoExceptionAndDescription =
            assertOkStatusCode && assertOkDescription && assertOkExceptionEmpty
          val assertOkWithException             =
            assertOkStatusCode && assertOkDescription && assertOkExceptionIsSet
          val assertErrorNoException            =
            assertErrorStatusCode && assertErrorExceptionEmpty && assertErrorDescription
          val assertErrorNoDescription          =
            assertErrorStatusCode && assertErrorException && assertErrorDescriptionEmpty

          final case class Error(message: String)

          for {
            _ <-
              (ZIO.fail(Error("OK")) @@
                tracer.aspects
                  .span(
                    "ok-no-exception",
                    statusMapper = StatusMapper.failure[Error](_ => StatusCode.OK)(e => Some(e.message))(_ => None)
                  )).either
            _ <-
              (ZIO.fail(Error("OK")) @@
                tracer.aspects
                  .span(
                    "ok-no-exception-1",
                    statusMapper = StatusMapper.failureNoException[Error](_ => StatusCode.OK)(e => Some(e.message))
                  )).either
            _ <-
              (ZIO.fail(Error("OK")) @@
                tracer.aspects
                  .span(
                    "ok-no-exception-and-description",
                    statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.OK)(_ => None)
                  )).either
            _ <-
              (ZIO.fail(Error("OK")) @@
                tracer.aspects.span(
                  "ok-with-exception",
                  statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.OK)(e =>
                    Some(new RuntimeException(e.message))
                  )
                )).either
            _ <-
              (ZIO.fail(Error("Error")) @@
                tracer.aspects.span(
                  "error-no-exception",
                  statusMapper = StatusMapper.failureNoException[Error](_ => StatusCode.ERROR)(e => Some(e.message))
                )).either

            _ <-
              (ZIO.fail(Error("Error")) @@
                tracer.aspects.span(
                  "error-no-description",
                  statusMapper = StatusMapper.failure[Error](_ => StatusCode.ERROR)(_ => None)(e =>
                    Some(new RuntimeException(e.message))
                  )
                )).either
            _ <-
              (ZIO.fail(Error("Error")) @@
                tracer.aspects.span(
                  "error-no-description-1",
                  statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.ERROR)(e =>
                    Some(new RuntimeException(e.message))
                  )
                )).either

            spans                      <- getFinishedSpans
            okNoException               = spans.find(_.getName == "ok-no-exception")
            okNoException1              = spans.find(_.getName == "ok-no-exception-1")
            okNoExceptionAndDescription = spans.find(_.getName == "ok-no-exception-and-description")
            okWithException             = spans.find(_.getName == "ok-with-exception")
            errorNoException            = spans.find(_.getName == "error-no-exception")
            errorNoDescription          = spans.find(_.getName == "error-no-description")
            errorNoDescription1         = spans.find(_.getName == "error-no-description-1")
          } yield assert(okNoException)(isSome(assertOkNoException)) &&
            assert(okNoException1)(isSome(assertOkNoException)) &&
            assert(okNoExceptionAndDescription)(isSome(assertOkNoExceptionAndDescription)) &&
            assert(okWithException)(isSome(assertOkWithException)) &&
            assert(errorNoException)(isSome(assertErrorNoException)) &&
            assert(errorNoDescription)(isSome(assertErrorNoDescription)) &&
            assert(errorNoDescription1)(isSome(assertErrorNoDescription))
        }
      },
      test("failureThrowable") {
        ZIO.serviceWithZIO[Tracer] { tracer =>
          val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
          val assertOkDescription = assertSpanDescription(equalTo(""))
          val assertOkException   = assertSpanException(
            hasSubset(List("exception.message" -> "OK", "exception.type" -> "java.lang.RuntimeException"))
          )

          val assertErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
          val assertErrorDescription = assertSpanDescription(equalTo(""))
          val assertErrorException   = assertSpanException(
            hasSubset(List("exception.message" -> "Error", "exception.type" -> "java.lang.RuntimeException"))
          )

          val assertOk    =
            assertOkStatusCode && assertOkDescription && assertOkException
          val assertError =
            assertErrorStatusCode && assertErrorException && assertErrorDescription

          for {
            _ <-
              (ZIO.fail(new RuntimeException("OK")) @@
                tracer.aspects
                  .span(
                    "ok",
                    statusMapper = StatusMapper.failureThrowable(_ => StatusCode.OK)
                  )).either
            _ <-
              (ZIO.fail(new RuntimeException("Error")) @@
                tracer.aspects.span(
                  "error",
                  statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR)
                )).either

            spans <- getFinishedSpans
            ok     = spans.find(_.getName == "ok")
            error  = spans.find(_.getName == "error")
          } yield assert(ok)(isSome(assertOk)) &&
            assert(error)(isSome(assertError))
        }
      }
    ).provide(tracerMockLayer(), ctxStorageLayer)

  private val spanWithLogAnnotationsSpec =
    suite("spans with log annotations")(
      test("with log annotations") {
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
      test("without log annotations") {
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
