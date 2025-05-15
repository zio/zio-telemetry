//> using scala "3.6.4"
//> using dep dev.zio::zio:2.1.17
//> using dep dev.zio::zio-opentelemetry:4.0.0-RC3

import zio.*
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.OpenTelemetry

object BaggageApp extends ZIOAppDefault {

  override def run =
    ZIO
      .serviceWithZIO[OpenTelemetry] { openTelemetry =>
        // Read user input
        Console.readLine.flatMap { message =>
          // Set baggage key/value
          openTelemetry.baggage.set("message", message) {
            for {
              // Read all baggage data including ZIO log annotations
              data <- ZIO.logAnnotate("message2", "annotation")(
                        openTelemetry.baggage.getAll
                      )
              // Print the resulting data
              _    <- Console.printLine(s"Baggage data: $data")
            } yield ()
          }
        }

      }
      .provide(
        OpenTelemetry.noop()
      )

}
