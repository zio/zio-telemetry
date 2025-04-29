package zio.telemetry.opentelemetry.baggage

import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.test.Assertion._
import zio.test._

object BaggageTest extends ZIOSpecDefault {

  def baggageLayer: ULayer[Baggage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped.map(Baggage.make(_)))

  def logAnnotatedBaggageLayer: ULayer[Baggage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped.map(Baggage.make(_, logAnnotated = true)))

  def spec: Spec[Environment with TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Baggage")(
        operationsSpec,
        logAnnotatedSpec
      )
    )

  private def operationsSpec =
    suite("operations")(
      test("set/get") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          baggage.get("some").map { value =>
            assert(value)(isSome(equalTo("thing")))
          } @@ baggage.aspects.set("some", "thing")
        }
      }.provideLayer(baggageLayer),
      test("set/getAll with metadata") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          baggage.getAllWithMetadata.map { result =>
            assert(result)(equalTo(Map("some" -> ("thing" -> "meta"))))
          } @@ baggage.aspects.setWithMetadata("some", "thing", "meta")
        }
      }.provideLayer(baggageLayer),
      test("remove") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          (
            for {
              thing   <- baggage.get("some")
              noThing <- baggage.get("some") @@ baggage.aspects.remove("some")
            } yield assert(thing)(isSome(equalTo("thing"))) && assert(noThing)(isNone)
          ) @@ baggage.aspects.set("some", "thing")
        }
      }.provideLayer(baggageLayer)
    )

  private def logAnnotatedSpec =
    suite("log annotated")(
      test("get") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate("zio", "annotation") {
            for {
              result <- baggage.get("zio")
            } yield assert(result)(isSome(equalTo("annotation")))
          }
        }
      },
      test("getAll") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar"), LogAnnotation("dog", "fox")) {
            for {
              result <- baggage.getAll
            } yield assert(result)(equalTo(Map("foo" -> "bar", "dog" -> "fox")))
          }
        }
      },
      test("set overrides a value of a key taken from log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar"), LogAnnotation("dog", "fox")) {
            baggage.getAll.map { result =>
              assert(result)(equalTo(Map("foo" -> "bark", "dog" -> "fox", "some" -> "thing")))
            } @@ baggage.aspects.set("foo", "bark") @@ baggage.aspects.set("some", "thing")
          }
        }
      },
      test("remove doesn't work for keys provided by log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            baggage.getAll.map { result =>
              assert(result)(equalTo(Map("foo" -> "bar")))
            } @@ baggage.aspects.remove("foo")
          }
        }
      },
      test("getAllWithMetadata returns a metadata provided by log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            for {
              result <- baggage.getAllWithMetadata
            } yield assert(result)(equalTo(Map("foo" -> ("bar" -> "zio log annotation"))))
          }
        }
      },
      test("setWithMetadata overrides a value with metadata taken from log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            baggage.getAllWithMetadata.map { result =>
              assert(result)(equalTo(Map("foo" -> ("bar" -> "baz"))))
            } @@ baggage.aspects.setWithMetadata("foo", "bar", "baz")
          }
        }
      }
    ).provideLayer(logAnnotatedBaggageLayer)

}
