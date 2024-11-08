import sbt.*

object Dependencies {

  object Versions {
    val opentracing           = "0.33.0"
    val opentelemetry         = "1.43.0"
    val opencensus            = "0.31.1"
    val scalaCollectionCompat = "2.12.0"
    val zio                   = "2.1.12"
    val zioLogging            = "2.3.2"
    val izumiReflect          = "2.3.10"
  }

  object Orgs {
    val zio                          = "dev.zio"
    val opentracing                  = "io.opentracing"
    val opentelemetry                = "io.opentelemetry"
    val opentelemetrySemconv         = "io.opentelemetry.semconv"
    val opentelemetryInstrumentation = "io.opentelemetry.instrumentation"
    val opencensus                   = "io.opencensus"
    val jaegertracing                = "io.jaegertracing"
    val scalaLangModules             = "org.scala-lang.modules"
    val typelevel                    = "org.typelevel"
    val softwaremillSttpClient3      = "com.softwaremill.sttp.client3"
    val slf4j                        = "org.slf4j"
    val grpc                         = "io.grpc"
    val logback                      = "ch.qos.logback"
  }

  private object ExampleVersions {
    val cats      = "2.7.0"
    val grpcNetty = "1.47.0"
    val jaeger    = "1.8.0"
    val slf4j     = "1.7.36"
    val sttp3     = "3.7.0"
    val zipkin    = "2.16.3"
    val zioJson   = "0.3.0-RC10"
    val zioConfig = "3.0.1"
    val zioHttp   = "3.0.0-RC2"
    val logback   = "1.4.11"
  }

  lazy val zio = Seq(
    Orgs.zio %% "zio"             % Versions.zio,
    Orgs.zio %% "izumi-reflect"   % Versions.izumiReflect,
    Orgs.zio %% "zio-stacktracer" % Versions.zio
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
    Orgs.opencensus        % "opencensus-api"          % Versions.opencensus,
    Orgs.opencensus        % "opencensus-impl"         % Versions.opencensus,
    Orgs.scalaLangModules %% "scala-collection-compat" % Versions.scalaCollectionCompat % Test
  )

  lazy val opentelemetryZioLogging = Seq(
    Orgs.opentelemetry % "opentelemetry-api"         % Versions.opentelemetry,
    Orgs.opentelemetry % "opentelemetry-context"     % Versions.opentelemetry,
    Orgs.opentelemetry % "opentelemetry-sdk-testing" % Versions.opentelemetry % Test,
    Orgs.zio          %% "zio-logging"               % Versions.zioLogging
  )

  lazy val example = zio ++ Seq(
    Orgs.typelevel               %% "cats-core"           % ExampleVersions.cats,
    Orgs.jaegertracing            % "jaeger-core"         % ExampleVersions.jaeger,
    Orgs.jaegertracing            % "jaeger-client"       % ExampleVersions.jaeger,
    Orgs.jaegertracing            % "jaeger-zipkin"       % ExampleVersions.jaeger,
    Orgs.softwaremillSttpClient3 %% "zio-json"            % ExampleVersions.sttp3,
    Orgs.zio                     %% "zio-json"            % ExampleVersions.zioJson,
    Orgs.zio                     %% "zio-config"          % ExampleVersions.zioConfig,
    Orgs.zio                     %% "zio-config-magnolia" % ExampleVersions.zioConfig,
    Orgs.zio                     %% "zio-config-typesafe" % ExampleVersions.zioConfig,
    // runtime to avoid warning in examples
    Orgs.slf4j                    % "slf4j-simple"        % ExampleVersions.slf4j % Runtime
  )

  lazy val opentracingExample = example ++ Seq(
    "io.zipkin.reporter2" % "zipkin-reporter"       % ExampleVersions.zipkin,
    "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % ExampleVersions.zipkin,
    Orgs.zio             %% "zio-http"              % ExampleVersions.zioHttp
  )

  lazy val opentelemetryExample = example ++ Seq(
    Orgs.opentelemetry        % "opentelemetry-exporter-otlp"         % Versions.opentelemetry,
    Orgs.opentelemetry        % "opentelemetry-exporter-logging-otlp" % Versions.opentelemetry,
    Orgs.opentelemetry        % "opentelemetry-sdk"                   % Versions.opentelemetry,
    Orgs.opentelemetrySemconv % "opentelemetry-semconv"               % "1.22.0-alpha",
    Orgs.grpc                 % "grpc-netty-shaded"                   % ExampleVersions.grpcNetty,
    Orgs.zio                 %% "zio-http"                            % ExampleVersions.zioHttp
  )

  lazy val opentelemetryInstrumentationExample = example ++ Seq(
    Orgs.opentelemetry                % "opentelemetry-exporter-otlp"        % Versions.opentelemetry,
    Orgs.opentelemetry                % "opentelemetry-sdk"                  % Versions.opentelemetry,
    Orgs.opentelemetrySemconv         % "opentelemetry-semconv"              % "1.22.0-alpha",
    Orgs.grpc                         % "grpc-netty-shaded"                  % ExampleVersions.grpcNetty,
    Orgs.opentelemetryInstrumentation % "opentelemetry-logback-appender-1.0" % "1.31.0-alpha",
    Orgs.zio                         %% "zio-http"                           % ExampleVersions.zioHttp,
    Orgs.zio                         %% "zio-logging"                        % Versions.zioLogging,
    Orgs.zio                         %% "zio-logging-slf4j2"                 % Versions.zioLogging,
    Orgs.logback                      % "logback-classic"                    % ExampleVersions.logback,
    Orgs.logback                      % "logback-core"                       % ExampleVersions.logback
  )

}
