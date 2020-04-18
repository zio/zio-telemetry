package zio.telemetry.opentracing.example

import zio.ZIO
import zio.clock.Clock

package object http {

  type AppTask[A] = ZIO[Clock, Throwable, A]

}
