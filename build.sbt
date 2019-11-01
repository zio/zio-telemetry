lazy val scala213 = "2.13.1"
lazy val scala212 = "2.12.10"
lazy val scala211 = "2.11.12"
lazy val supportedScalaVersions = List(scala213, scala212, scala211)

val zioVersion = "1.0.0-RC16"
val opentracingVersion = "0.33.0"

ThisBuild / name := "zio-telemetry"
ThisBuild / organization := "dev.zio"
ThisBuild / version := "0.33.0.1"
ThisBuild / scalaVersion := scala213

lazy val `zio-opentracing` =
  (project in file("."))
    .settings(
      crossScalaVersions := supportedScalaVersions,
      libraryDependencies := Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-test" % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
        "io.opentracing" % "opentracing-api" % opentracingVersion,
        "io.opentracing" % "opentracing-mock" % opentracingVersion % Test,
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1"
      ),
      testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
      scalacOptions ++= Seq(
        "-deprecation",
        "-Xfatal-warnings",
        "-encoding",
        "utf8",
        "-deprecation",
        "-unchecked",
        "-Xlint:_",
        "-feature",
        "-Yrangepos",
        "-Ywarn-value-discard",
        "-Ywarn-dead-code"
      ) ++ {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, 13)) =>
            Seq(
              "-Ywarn-unused:_"
            )
          case Some((2, 12)) =>
            Seq(
              "-Ywarn-unused:_",
              "-Ypartial-unification",
              "-Xexperimental"
            )
          case Some((2, 11)) =>
            Seq(
              "-Ywarn-unused",
              "-Ypartial-unification",
              "-Xexperimental"
            )
          case _ => Seq.empty[String]
        }
      }
    )
