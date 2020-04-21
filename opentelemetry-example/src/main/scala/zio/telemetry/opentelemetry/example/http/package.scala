package zio.telemetry.opentelemetry.example

import zio.{ Has, RIO }
import zio.clock.Clock
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.Configuration

package object http {

  type Client = Has[Client.Service]

  type AppEnv     = Tracing with Configuration with Clock with Client
  type AppTask[A] = RIO[AppEnv, A]

}
