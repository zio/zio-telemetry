package zio.telemetry.opentracing.example

import scala.jdk.CollectionConverters

object JavaConverters {
  val collection: CollectionConverters.type = scala.jdk.CollectionConverters
}