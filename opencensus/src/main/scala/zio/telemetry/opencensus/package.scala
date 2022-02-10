package zio.telemetry

import io.opencensus.trace.Status

package object opencensus {
  object implicits extends Attributes.implicits

  type ErrorMapper[E] = PartialFunction[E, Status]
}
