package zio.telemetry.opentelemetry.config

import zio.{ Task, ZIO, ZLayer }
import pureconfig.generic.auto._

object Configuration {

  trait Service {
    val load: Task[Config]
  }

  object Live extends Service {
    val load: Task[Config] =
      Task.effect(pureconfig.ConfigSource.resources("opentelemetry.application.conf").loadOrThrow[Config])
  }

  val live: ZLayer[Any, Throwable, Configuration] = ZLayer.succeed(Live)

  val load: ZIO[Configuration, Throwable, Config] = ZIO.accessM[Configuration] { _.get.load }

}
