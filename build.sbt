import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://github.com/zio/zio-telemetry/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "mijicd",
        "Dejan Mijic",
        "dmijic@acm.org",
        url("https://github.com/mijicd")
      ),
      Developer(
        "runtologist",
        "Simon Schenk",
        "simon@schenk-online.net",
        url("https://github.com/runtologist")
      )
    ),
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    )
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val root =
  project
    .in(file("."))
    .settings(skip in publish := true)
    .aggregate(opentracing, opentelemetry, opentracingExample, opentelemetryExample)

val http4sVersion        = "0.21.3"
val jaegerVersion        = "1.2.0"
val sttpVersion          = "2.0.9"
val opentracingVersion   = "0.33.0"
val opentelemetryVersion = "0.3.0"
val zipkinVersion        = "2.12.3"
val zioVersion           = "1.0.0-RC18-2"

lazy val opentracing =
  project
    .in(file("opentracing"))
    .settings(stdSettings("zio-opentracing"))
    .settings(
      libraryDependencies := Seq(
        "dev.zio"                %% "zio"                     % zioVersion,
        "dev.zio"                %% "zio-test"                % zioVersion % Test,
        "dev.zio"                %% "zio-test-sbt"            % zioVersion % Test,
        "io.opentracing"         % "opentracing-api"          % opentracingVersion,
        "io.opentracing"         % "opentracing-noop"         % opentracingVersion,
        "io.opentracing"         % "opentracing-mock"         % opentracingVersion % Test,
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6"
      )
    )

lazy val opentelemetry =
  project
    .in(file("opentelemetry"))
    .settings(stdSettings("zio-opentelemetry"))
    .settings(
      libraryDependencies := Seq(
        "dev.zio"                %% "zio"                             % zioVersion,
        "dev.zio"                %% "zio-test"                        % zioVersion % Test,
        "dev.zio"                %% "zio-test-sbt"                    % zioVersion % Test,
        "io.opentelemetry"       % "opentelemetry-api"                % opentelemetryVersion,
        "io.opentelemetry"       % "opentelemetry-exporters-inmemory" % opentelemetryVersion % Test,
        "org.scala-lang.modules" %% "scala-collection-compat"         % "2.1.4"
      )
    )

Global / testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

lazy val exampleDependencies = Seq(
  "org.typelevel"                %% "cats-core"                     % "2.1.1",
  "io.circe"                     %% "circe-generic"                 % "0.13.0",
  "org.http4s"                   %% "http4s-core"                   % http4sVersion,
  "org.http4s"                   %% "http4s-blaze-server"           % http4sVersion,
  "org.http4s"                   %% "http4s-dsl"                    % http4sVersion,
  "org.http4s"                   %% "http4s-circe"                  % http4sVersion,
  "io.jaegertracing"             % "jaeger-core"                    % jaegerVersion,
  "io.jaegertracing"             % "jaeger-client"                  % jaegerVersion,
  "io.jaegertracing"             % "jaeger-zipkin"                  % jaegerVersion,
  "com.github.pureconfig"        %% "pureconfig"                    % "0.12.3",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % sttpVersion,
  "com.softwaremill.sttp.client" %% "circe"                         % sttpVersion,
  "dev.zio"                      %% "zio-interop-cats"              % "2.0.0.0-RC12"
)

lazy val opentracingExample =
  project
    .in(file("opentracing-example"))
    .settings(stdSettings("opentracing-example"))
    .settings(skip in publish := true)
    .settings(
      libraryDependencies := exampleDependencies ++ Seq(
        "io.zipkin.reporter2" % "zipkin-reporter"       % zipkinVersion,
        "io.zipkin.reporter2" % "zipkin-sender-okhttp3" % zipkinVersion
      )
    )
    .dependsOn(opentracing)

lazy val opentelemetryExample =
  project
    .in(file("opentelemetry-example"))
    .settings(stdSettings("opentelemetry-example"))
    .settings(skip in publish := true)
    .settings(
      libraryDependencies := exampleDependencies ++ Seq(
        "io.opentelemetry" % "opentelemetry-exporters-jaeger" % opentelemetryVersion,
        "io.opentelemetry" % "opentelemetry-sdk"              % opentelemetryVersion,
        "io.grpc"          % "grpc-netty-shaded"              % "1.28.0"
      )
    )
    .dependsOn(opentelemetry)
