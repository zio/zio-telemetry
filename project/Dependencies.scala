import sbt._

object Dependencies {
  object Versions {
    val http4s        = "0.21.3"
    val jaeger        = "1.3.1"
    val sttp          = "2.1.5"
    val opentracing   = "0.33.0"
    val opentelemetry = "0.3.0"
    val opencensus    = "0.26.0"
    val zipkin        = "2.15.0"
    val zio           = "1.0.0-RC21-2"
  }

  lazy val zio = Seq(
    "dev.zio" %% "zio"          % Versions.zio,
    "dev.zio" %% "zio-test"     % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
  )

  lazy val opentracing = zio ++ Seq(
    "io.opentracing"         % "opentracing-api"          % Versions.opentracing,
    "io.opentracing"         % "opentracing-noop"         % Versions.opentracing,
    "io.opentracing"         % "opentracing-mock"         % Versions.opentracing % Test,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6"
  )

  lazy val opentelemetry = zio ++ Seq(
    "io.opentelemetry"       % "opentelemetry-api"                % Versions.opentelemetry,
    "io.opentelemetry"       % "opentelemetry-exporters-inmemory" % Versions.opentelemetry % Test,
    "org.scala-lang.modules" %% "scala-collection-compat"         % "2.1.6"
  )

  lazy val opencensus = zio ++ Seq(
    "io.opencensus" % "opencensus-api"               % Versions.opencensus,
    "io.opencensus" % "opencensus-impl"              % Versions.opencensus,
    "io.opencensus" % "opencensus-contrib-http-util" % Versions.opencensus
  )

  lazy val example = Seq(
    "org.typelevel"                %% "cats-core"                     % "2.1.1",
    "io.circe"                     %% "circe-generic"                 % "0.13.0",
    "org.http4s"                   %% "http4s-core"                   % Versions.http4s,
    "org.http4s"                   %% "http4s-blaze-server"           % Versions.http4s,
    "org.http4s"                   %% "http4s-dsl"                    % Versions.http4s,
    "org.http4s"                   %% "http4s-circe"                  % Versions.http4s,
    "io.jaegertracing"             % "jaeger-core"                    % Versions.jaeger,
    "io.jaegertracing"             % "jaeger-client"                  % Versions.jaeger,
    "io.jaegertracing"             % "jaeger-zipkin"                  % Versions.jaeger,
    "com.github.pureconfig"        %% "pureconfig"                    % "0.13.0",
    "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % Versions.sttp,
    "com.softwaremill.sttp.client" %% "circe"                         % Versions.sttp,
    "dev.zio"                      %% "zio-interop-cats"              % "2.0.0.0-RC14"
  )

  lazy val opentracingExample = example ++ Seq(
    "io.zipkin.reporter2" % "zipkin-reporter"       % Versions.zipkin,
    "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % Versions.zipkin
  )

  lazy val opentelemetryExample = example ++ Seq(
    "io.opentelemetry" % "opentelemetry-exporters-jaeger" % Versions.opentelemetry,
    "io.opentelemetry" % "opentelemetry-sdk"              % Versions.opentelemetry,
    "io.grpc"          % "grpc-netty-shaded"              % "1.31.0"
  )
}
