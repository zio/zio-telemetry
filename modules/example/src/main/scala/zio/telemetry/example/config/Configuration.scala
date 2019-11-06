package zio.telemetry.example.config

import pureconfig.ConfigSource
import pureconfig.generic.auto._
import zio.{ RIO, Task }

trait Configuration extends Serializable {
  val config: Configuration.Service[Any]
}

object Configuration {

  trait Service[R] {
    val load: RIO[R, ProxyConfig]
  }

  trait Live extends Configuration {
    override val config: Service[Any] = new Service[Any] {
      val load: Task[ProxyConfig] = Task.effect(ConfigSource.default.loadOrThrow[ProxyConfig])
    }
  }

  object Live extends Live

}
