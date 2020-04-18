package zio.telemetry.opentelemetry.tracing.example

import zio.Has

package object config {

  type Configuration = Has[Configuration.Service]

}
