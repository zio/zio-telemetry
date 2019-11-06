package zio.telemetry.example

import zio.RIO

package object config extends Configuration.Service[Configuration] {
  val load: RIO[Configuration, ProxyConfig] = RIO.accessM(_.config.load)
}
