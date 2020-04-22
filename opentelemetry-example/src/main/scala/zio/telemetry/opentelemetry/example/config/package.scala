package zio.telemetry.opentelemetry.example

import zio.Has

package object config {

  type Configuration = Has[Config]

}
