//> using scala "2.13.12"
//> using dep dev.zio::zio:2.0.20
//> using dep dev.zio::zio-opentelemetry:3.0.0-RC17+40-e7300350+20231227-2200-SNAPSHOT

import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.OpenTelemetry

object BaggageApp extends ZIOAppDefault {

  override def run =
    ZIO
      .serviceWithZIO[Baggage] { baggage =>
        for {
          // Read user input
          message <- Console.readLine
          // Set baggage key/value
          _       <- baggage.set("message", message)
          // Read all baggage data including ZIO log annotations
          data    <- ZIO.logAnnotate("message2", "annotation")(
                       baggage.getAll
                     )
          // Print the resulting data
          _       <- Console.printLine(s"Baggage data: $data")
        } yield message
      }
      .provide(
        ContextStorage.fiberRef,
        OpenTelemetry.baggage(logAnnotated = true)
      )

}
