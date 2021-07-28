package zio.telemetry.opentracing.example

import zio.{ ExitCode, ZEnv, ZIO, App }
import zio.clock.Clock
import zio.console.putStrLn
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.Configuration
import zio.telemetry.opentracing.example.http.StatusService
import zhttp.service.{ EventLoopGroup, Server }
import zhttp.service.server.ServerChannelFactory

object BackendServer extends App {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      ZIO.runtime[Clock].flatMap { implicit runtime =>
        for {
          conf   <- Configuration.load.provideLayer(Configuration.live)
          service = makeService(conf.tracer.host, "zio-backend")
          result <- (Server.port(conf.backend.port) ++ Server.app(StatusService.status(service))).make
            .use(_ => putStrLn(s"BackendServer started at ${conf.backend.port}") *> ZIO.never)
            .provideCustomLayer(ServerChannelFactory.auto ++ EventLoopGroup.auto(0)).exitCode
        } yield result
      }

    exit orElse ZIO.succeed(ExitCode.failure)
  }
}
