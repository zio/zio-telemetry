import sbt._

object Dependencies {
  object Versions {
    val opentracing           = "0.33.0"
    val opentelemetry         = "1.18.0"
    val opencensus            = "0.31.1"
    val scalaCollectionCompat = "2.8.1"
    val zio                   = "2.0.3"
  }

  private object ExampleVersions {
    val cats      = "2.7.0"
    val grpcNetty = "1.47.0"
    val jaeger    = "1.8.0"
    val slf4j     = "1.7.36"
    val sttp3     = "3.7.0"
    val zipkin    = "2.16.3"
    val zioHttp   = "2.0.0-RC10"
    val zioJson   = "0.3.0-RC10"
    val zioConfig = "3.0.1"
  }

  lazy val zio = Seq(
    "dev.zio" %% "zio"          % Versions.zio,
    "dev.zio" %% "zio-test"     % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
  )

  lazy val opentracing = zio ++ Seq(
    "io.opentracing"          % "opentracing-api"         % Versions.opentracing,
    "io.opentracing"          % "opentracing-noop"        % Versions.opentracing,
    "org.scala-lang.modules" %% "scala-collection-compat" % Versions.scalaCollectionCompat,
    "io.opentracing"          % "opentracing-mock"        % Versions.opentracing % Test
  )

  lazy val opentelemetry = zio ++ Seq(
    "io.opentelemetry"        % "opentelemetry-api"         % Versions.opentelemetry,
    "io.opentelemetry"        % "opentelemetry-context"     % Versions.opentelemetry,
    "org.scala-lang.modules" %% "scala-collection-compat"   % Versions.scalaCollectionCompat,
    "io.opentelemetry"        % "opentelemetry-sdk-testing" % Versions.opentelemetry % Test
  )

  lazy val opencensus = zio ++ Seq(
    "io.opencensus" % "opencensus-api"               % Versions.opencensus,
    "io.opencensus" % "opencensus-impl"              % Versions.opencensus,
    "io.opencensus" % "opencensus-contrib-http-util" % Versions.opencensus
  )

  lazy val example = Seq(
    "org.typelevel"                 %% "cats-core"                     % ExampleVersions.cats,
    "io.jaegertracing"               % "jaeger-core"                   % ExampleVersions.jaeger,
    "io.jaegertracing"               % "jaeger-client"                 % ExampleVersions.jaeger,
    "io.jaegertracing"               % "jaeger-zipkin"                 % ExampleVersions.jaeger,
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % ExampleVersions.sttp3,
    "com.softwaremill.sttp.client3" %% "zio-json"                      % ExampleVersions.sttp3,
    "io.d11"                        %% "zhttp"                         % ExampleVersions.zioHttp,
    "dev.zio"                       %% "zio-json"                      % ExampleVersions.zioJson,
    "dev.zio"                       %% "zio-config"                    % ExampleVersions.zioConfig,
    "dev.zio"                       %% "zio-config-magnolia"           % ExampleVersions.zioConfig,
    "dev.zio"                       %% "zio-config-typesafe"           % ExampleVersions.zioConfig,
    // runtime to avoid warning in examples
    "org.slf4j"                      % "slf4j-simple"                  % ExampleVersions.slf4j % Runtime
  )

  lazy val opentracingExample = example ++ Seq(
    "io.zipkin.reporter2" % "zipkin-reporter"       % ExampleVersions.zipkin,
    "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % ExampleVersions.zipkin
  )

  lazy val opentelemetryExample = example ++ Seq(
    "io.opentelemetry" % "opentelemetry-exporter-jaeger" % Versions.opentelemetry,
    "io.opentelemetry" % "opentelemetry-sdk"             % Versions.opentelemetry,
    "io.grpc"          % "grpc-netty-shaded"             % ExampleVersions.grpcNetty
  )
}
