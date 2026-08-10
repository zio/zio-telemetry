package zio.telemetry.opencensus

// format: off
// scalafix:off OrganizeImports
// `export` is a Scala 3 keyword, so these package references must remain escaped.
import io.opencensus.trace.`export`.SpanData
import io.opencensus.trace.`export`.SpanExporter.Handler
// scalafix:on OrganizeImports
// format: on
import io.opencensus.trace.{Tracing => OTracing}
import zio.{Ref, Runtime, Unsafe, ZIO}

import java.util
import scala.jdk.CollectionConverters._

object InMemoryExporter {

  private val runtime = Runtime.default

  def register(): ZIO[Any, Nothing, Ref[List[SpanData]]] =
    Ref
      .make(List.empty[SpanData])
      .map { x =>
        OTracing.getExportComponent.getSpanExporter.registerHandler("InMemoryExporter", new InMemoryExporter(x))
        x
      }

  class InMemoryExporter(finishedSpans: Ref[List[SpanData]]) extends Handler {
    override def `export`(spanDataList: util.Collection[SpanData]): Unit =
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.run(finishedSpans.update(x => x ++ spanDataList.asScala.toList)).foldExit(_ => (), _ => ())
      }
  }
}
