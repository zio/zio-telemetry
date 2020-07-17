package zio.telemetry.opentracing
import io.opentracing.Tracer

final case class Context(name: String)

object Context {
  val entrypointExtractor: EntrypointExtractor[Context] = new EntrypointExtractor[Context] {
    def extract(tracer: Tracer, context: Context): Entrypoint = {
      Entrypoint(context.name, null)
    }
  }
}
