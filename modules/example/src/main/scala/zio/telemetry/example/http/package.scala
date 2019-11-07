package zio.telemetry.example

import zio.ZIO
import zio.clock.Clock

package object http {

  type AppTask[A] = ZIO[Clock, Throwable, A]

}
