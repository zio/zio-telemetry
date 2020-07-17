package zio.telemetry.opentracing.example

import io.opentracing.Tracer
import io.opentracing.propagation.{ Format, TextMapAdapter }
import org.http4s.Headers
import zio.telemetry.opentracing.example.JavaConverters.collection._
import zio.telemetry.opentracing.{ Entrypoint, EntrypointExtractor }

final case class HttpOperation(operationName: String, headers: Headers)

object HttpOperation {
  val entrypointExtractor: EntrypointExtractor[HttpOperation] = new EntrypointExtractor[HttpOperation] {
    def extract(tracer: Tracer, context: HttpOperation): Entrypoint = {
      val headers = context.headers.toList.map(x => x.name.value -> x.value).toMap
      Entrypoint(context.operationName, tracer.extract(Format.Builtin.TEXT_MAP, new TextMapAdapter(headers.asJava)))
    }
  }
}
