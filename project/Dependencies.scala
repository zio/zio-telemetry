import sbt._

object Dependencies {

  object Versions {
    val opentracing           = "0.33.0"
    val opentelemetry         = "1.25.0"
    val opencensus            = "0.31.1"
    val scalaCollectionCompat = "2.9.0"
    val zio                   = "2.0.13"
  }

  object Orgs {
    val zio                     = "dev.zio"
    val opentracing             = "io.opentracing"
    val opentelemetry           = "io.opentelemetry"
    val opencensus              = "io.opencensus"
    val jaegertracing           = "io.jaegertracing"
    val scalaLangModules        = "org.scala-lang.modules"
    val typelevel               = "org.typelevel"
    val d11                     = "io.d11"
    val softwaremillSttpClient3 = "com.softwaremill.sttp.client3"
    val slf4j                   = "org.slf4j"
    val grpc                    = "io.grpc"
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
    Orgs.zio %% "zio"          % Versions.zio,
    Orgs.zio %% "zio-test"     % Versions.zio % Test,
    Orgs.zio %% "zio-test-sbt" % Versions.zio % Test
  )

  lazy val opentracing = zio ++ Seq(
    Orgs.opentracing       % "opentracing-api"         % Versions.opentracing,
    Orgs.opentracing       % "opentracing-noop"        % Versions.opentracing,
    Orgs.scalaLangModules %% "scala-collection-compat" % Versions.scalaCollectionCompat,
    Orgs.opentracing       % "opentracing-mock"        % Versions.opentracing % Test
  )

  lazy val opentelemetry = zio ++ Seq(
    Orgs.opentelemetry     % "opentelemetry-api"         % Versions.opentelemetry,
    Orgs.opentelemetry     % "opentelemetry-context"     % Versions.opentelemetry,
    Orgs.scalaLangModules %% "scala-collection-compat"   % Versions.scalaCollectionCompat,
    Orgs.opentelemetry     % "opentelemetry-sdk-testing" % Versions.opentelemetry % Test
  )

  lazy val opencensus = zio ++ Seq(
    Orgs.opencensus % "opencensus-api"               % Versions.opencensus,
    Orgs.opencensus % "opencensus-impl"              % Versions.opencensus,
    Orgs.opencensus % "opencensus-contrib-http-util" % Versions.opencensus
  )

  lazy val example = Seq(
    Orgs.typelevel               %% "cats-core"                     % ExampleVersions.cats,
    Orgs.jaegertracing            % "jaeger-core"                   % ExampleVersions.jaeger,
    Orgs.jaegertracing            % "jaeger-client"                 % ExampleVersions.jaeger,
    Orgs.jaegertracing            % "jaeger-zipkin"                 % ExampleVersions.jaeger,
    Orgs.softwaremillSttpClient3 %% "async-http-client-backend-zio" % ExampleVersions.sttp3,
    Orgs.softwaremillSttpClient3 %% "zio-json"                      % ExampleVersions.sttp3,
    Orgs.d11                     %% "zhttp"                         % ExampleVersions.zioHttp,
    Orgs.zio                     %% "zio-json"                      % ExampleVersions.zioJson,
    Orgs.zio                     %% "zio-config"                    % ExampleVersions.zioConfig,
    Orgs.zio                     %% "zio-config-magnolia"           % ExampleVersions.zioConfig,
    Orgs.zio                     %% "zio-config-typesafe"           % ExampleVersions.zioConfig,
    // runtime to avoid warning in examples
    Orgs.slf4j                    % "slf4j-simple"                  % ExampleVersions.slf4j % Runtime
  )

  lazy val opentracingExample = example ++ Seq(
    "io.zipkin.reporter2" % "zipkin-reporter"       % ExampleVersions.zipkin,
    "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % ExampleVersions.zipkin
  )

  lazy val opentelemetryExample = example ++ Seq(
    Orgs.opentelemetry % "opentelemetry-exporter-jaeger" % Versions.opentelemetry,
    Orgs.opentelemetry % "opentelemetry-sdk"             % Versions.opentelemetry,
    Orgs.grpc          % "grpc-netty-shaded"             % ExampleVersions.grpcNetty
  )

}
