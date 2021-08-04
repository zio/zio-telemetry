package zio.telemetry.opentelemetry.example

import zio.Has

package object http {
  type Client = Has[Client.Service]
}
