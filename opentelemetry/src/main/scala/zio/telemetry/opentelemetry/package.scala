package zio.telemetry

import zio.Has

package object opentelemetry {
  type Tracing = Has[Tracing.Service]
}
