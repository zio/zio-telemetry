package zio.telemetry.opentelemetry.example.config

import pureconfig.{ ConfigReader, ConfigSource }
import pureconfig.error.CannotConvert
import pureconfig.generic.auto._
import sttp.model.Uri
import zio.{ Task, TaskLayer, URIO, ZIO, ZLayer }

object Configuration {
  implicit val uriReader =
    ConfigReader.fromString(str => Uri.parse(str).left.map(CannotConvert(str, "Uri", _)))

  val live: TaskLayer[Configuration] = ZLayer.fromEffect(Task(ConfigSource.default.loadOrThrow[Config]))

  def get: URIO[Configuration, Config] = ZIO.access[Configuration](_.get)
}
