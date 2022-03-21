import sbt._

object Dependencies {
  object Versions {
    val jaeger        = "1.6.0"
    val sttp3         = "3.5.1"
    val opentracing   = "0.33.0"
    val opentelemetry = "1.11.0"
    val opencensus    = "0.31.0"
    val zipkin        = "2.16.3"
    val zio           = "2.0.0-RC3"
    val zioHttp       = "2.0.0-RC4+1-b364635d-SNAPSHOT" // TODO
    val zioJson       = "0.3.0-RC3"
    val zioConfig     = "3.0.0-RC5"
  }

  lazy val zio = Seq(
    "dev.zio" %% "zio"          % Versions.zio,
    "dev.zio" %% "zio-managed"  % Versions.zio,
    "dev.zio" %% "zio-test"     % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
  )

  lazy val opentracing = zio ++ Seq(
    "io.opentracing"          % "opentracing-api"         % Versions.opentracing,
    "io.opentracing"          % "opentracing-noop"        % Versions.opentracing,
    "io.opentracing"          % "opentracing-mock"        % Versions.opentracing % Test,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.6.0"
  )

  lazy val opentelemetry = zio ++ Seq(
    "io.opentelemetry"        % "opentelemetry-api"         % Versions.opentelemetry,
    "io.opentelemetry"        % "opentelemetry-context"     % Versions.opentelemetry,
    "io.opentelemetry"        % "opentelemetry-sdk-testing" % Versions.opentelemetry % Test,
    "org.scala-lang.modules" %% "scala-collection-compat"   % "2.6.0"
  )

  lazy val opencensus = zio ++ Seq(
    "io.opencensus" % "opencensus-api"               % Versions.opencensus,
    "io.opencensus" % "opencensus-impl"              % Versions.opencensus,
    "io.opencensus" % "opencensus-contrib-http-util" % Versions.opencensus
  )

  lazy val example = Seq(
    "org.typelevel"                 %% "cats-core"                     % "2.6.1",
    "io.jaegertracing"               % "jaeger-core"                   % Versions.jaeger,
    "io.jaegertracing"               % "jaeger-client"                 % Versions.jaeger,
    "io.jaegertracing"               % "jaeger-zipkin"                 % Versions.jaeger,
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % Versions.sttp3,
    "com.softwaremill.sttp.client3" %% "zio-json"                      % Versions.sttp3,
    "io.d11"                        %% "zhttp"                         % Versions.zioHttp,
    "dev.zio"                       %% "zio-json"                      % Versions.zioJson,
    "dev.zio"                       %% "zio-config"                    % Versions.zioConfig,
    "dev.zio"                       %% "zio-config-magnolia"           % Versions.zioConfig,
    "dev.zio"                       %% "zio-config-typesafe"           % Versions.zioConfig
  )

  lazy val opentracingExample = example ++ Seq(
    "io.zipkin.reporter2" % "zipkin-reporter"       % Versions.zipkin,
    "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % Versions.zipkin
  )

  lazy val opentelemetryExample = example ++ Seq(
    "io.opentelemetry" % "opentelemetry-exporter-jaeger" % Versions.opentelemetry,
    "io.opentelemetry" % "opentelemetry-sdk"             % Versions.opentelemetry,
    "io.grpc"          % "grpc-netty-shaded"             % "1.40.1"
  )
}
