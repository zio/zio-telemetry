package zio.telemetry.opentracing.example

import zio.Has

package object config {

  type Configuration = Has[Configuration.Service]

}
