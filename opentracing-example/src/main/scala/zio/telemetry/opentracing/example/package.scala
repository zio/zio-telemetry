package zio.telemetry.opentracing

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.Task

package object example {

  type Backend = SttpBackend[Task, ZioStreams with WebSockets]

}
