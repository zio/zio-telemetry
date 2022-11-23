package zio.telemetry.opentelemetry.baggage

import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.test._
import zio.test.Assertion._

object BaggageTest extends ZIOSpecDefault {

  def baggageLayer: ULayer[Baggage] =
    ContextStorage.fiberRef >>> Baggage.live

  def spec =
    suite("zio opentelemetry")(
      suite("Baggage")(
        test("set/get") {
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _     <- baggage.set("some", "thing")
              value <- baggage.get("some")
            } yield assert(value)(isSome(equalTo("thing")))
          }
        },
        test("set/getAll with metadata") {
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _      <- baggage.setWithMetadata("some", "thing", "meta")
              result <- baggage.getAllWithMetadata
            } yield assert(result)(equalTo(Map("some" -> ("thing" -> "meta"))))
          }
        },
        test("remove") {
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _       <- baggage.set("some", "thing")
              thing   <- baggage.get("some")
              _       <- baggage.remove("some")
              noThing <- baggage.get("some")
            } yield assert(thing)(isSome(equalTo("thing"))) && assert(noThing)(isNone)
          }
        }
      )
    ).provideLayer(baggageLayer)

}
