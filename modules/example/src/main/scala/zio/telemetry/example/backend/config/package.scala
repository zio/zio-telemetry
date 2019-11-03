package zio.telemetry.example.backend

import zio.RIO

package object config extends Configuration.Service[Configuration] {
  val load: RIO[Configuration, Config] = RIO.accessM(_.config.load)
}
