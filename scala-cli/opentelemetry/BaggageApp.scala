//> using scala "3.5.2"
//> using dep dev.zio::zio:2.1.13
//> using dep dev.zio::zio-opentelemetry:3.0.2

import zio.*
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
        OpenTelemetry.baggage(logAnnotated = true),
        OpenTelemetry.contextZIO
      )

}
