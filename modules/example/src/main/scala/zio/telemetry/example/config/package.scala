package zio.telemetry.example

import zio.Has

package object config {

  type Configuration = Has[Configuration.Service]

}
