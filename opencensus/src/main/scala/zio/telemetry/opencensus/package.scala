package zio.telemetry

import io.opencensus.trace.Status

import zio.Has
package object opencensus {
  object implicits extends Attributes.implicits

  type Tracing        = Has[Tracing.Service]
  type ErrorMapper[E] = PartialFunction[E, Status]
}
