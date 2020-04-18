package zio.opentelemetry

import zio.Has

package object tracing {
  type Tracing = Has[Tracing.Service]
}
