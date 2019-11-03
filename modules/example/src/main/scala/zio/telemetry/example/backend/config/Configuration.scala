package zio.telemetry.example.backend.config

import pureconfig.ConfigSource
import zio.{ RIO, Task }
import pureconfig.generic.auto._

trait Configuration extends Serializable {
  val config: Configuration.Service[Any]
}

object Configuration {

  trait Service[R] {
    val load: RIO[R, Config]
  }

  trait Live extends Configuration {
    val config: Service[Any] = new Service[Any] {
      val load: Task[Config] = Task.effect(ConfigSource.default.loadOrThrow[Config])
    }
  }

  object Live extends Live
}
