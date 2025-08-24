val zioSbtVersion = "0.4.0-alpha.33"

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"          % "3.0.2")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"                % "0.6.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"                 % "2.0.13")
addSbtPlugin("org.typelevel"                     % "sbt-tpolecat"              % "0.5.2")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"              % "2.5.5")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"                  % "2.7.2")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"             % "2.3.1")
addSbtPlugin("dev.zio"                           % "zio-sbt-ci"                % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-ecosystem"         % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-website"           % zioSbtVersion)
addSbtPlugin("com.typesafe"                      % "sbt-mima-plugin"           % "1.1.4")
addSbtPlugin("com.github.cb372"                  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("ch.epfl.scala"                     % "sbt-missinglink"           % "0.3.6")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.10"
libraryDependencies += "com.spotify"   % "missinglink-core" % "0.2.11"

resolvers ++= Resolver.sonatypeOssRepos("public")
