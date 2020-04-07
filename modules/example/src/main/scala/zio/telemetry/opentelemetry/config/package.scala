package zio.telemetry.opentelemetry

import zio.Has

package object config {

  type Configuration = Has[Configuration.Service]

}
