package zio.opentelemetry

import java.util.concurrent.TimeUnit

import zio.{ clock, Has, ZIO }
import zio.clock.Clock

package object tracing {
  type Tracing = Has[Tracing.Service]

  object PropagationFormat {
    type Key       = String
    type Value     = String
    type Reader[C] = (C, Key) => Option[Value]
    type Writer[C] = (C, Key, Value) => Unit
  }

  private[opentelemetry] def currentNanos: ZIO[Clock, Nothing, Long] = clock.currentTime(TimeUnit.NANOSECONDS)
}
