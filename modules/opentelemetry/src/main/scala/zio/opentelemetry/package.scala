package zio

import java.util.concurrent.TimeUnit

import zio.clock.Clock

package object opentelemetry {
  type OpenTelemetry = Has[OpenTelemetry.Service]

  object PropagationFormat {
    type Key       = String
    type Value     = String
    type Reader[C] = (C, Key) => Option[Value]
    type Writer[C] = (C, Key, Value) => Unit
  }

  def currentNanos: ZIO[Clock, Nothing, Long] = clock.currentTime(TimeUnit.NANOSECONDS)
}
