package zio.telemetry.opentracing.example.config

import pureconfig.ConfigSource
import pureconfig.generic.auto._
import zio.Task
import zio.ZLayer
import zio.ZIO

object Configuration {

  trait Service {
    val load: Task[Config]
  }

  object Live extends Service {
    val load: Task[Config] = Task.effect(ConfigSource.default.loadOrThrow[Config])
  }

  val live: ZLayer[Any, Throwable, Configuration] = ZLayer.succeed(Live)

  val load: ZIO[Configuration, Throwable, Config] = ZIO.accessM[Configuration] { _.get.load }

}
