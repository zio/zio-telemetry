package zio.telemetry.opentelemetry.testkit.common

import io.opentelemetry.sdk.common.{InstrumentationScopeInfo => JInstrumentationScopeInfo}

case class InstrumentationScopeInfo(
  name: String,
  version: String,
  schemaUrl: String,
  attributes: Attributes
)

object InstrumentationScopeInfo {
  def apply(underlying: JInstrumentationScopeInfo): InstrumentationScopeInfo =
    InstrumentationScopeInfo(
      name = underlying.getName,
      version = underlying.getVersion,
      schemaUrl = underlying.getSchemaUrl,
      attributes = Attributes(underlying.getAttributes)
    )
}
