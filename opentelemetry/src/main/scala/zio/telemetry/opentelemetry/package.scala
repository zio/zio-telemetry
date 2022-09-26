package zio.telemetry

import io.opentelemetry.api.trace.StatusCode

package object opentelemetry {

  type ErrorMapper[E] = PartialFunction[E, StatusCode]

}
