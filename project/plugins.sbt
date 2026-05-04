val zioSbtVersion = "0.5.1"

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"          % "3.0.2")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"                % "0.6.1")
addSbtPlugin("org.typelevel"                     % "sbt-tpolecat"              % "0.5.3")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"              % "2.6.0")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"                  % "2.9.0")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"             % "2.4.4")
addSbtPlugin("dev.zio"                           % "zio-sbt-ci"                % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-ecosystem"         % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-website"           % zioSbtVersion)
addSbtPlugin("com.typesafe"                      % "sbt-mima-plugin"           % "1.1.5")
addSbtPlugin("com.github.cb372"                  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("ch.epfl.scala"                     % "sbt-missinglink"           % "0.3.6")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "3.0.1"
libraryDependencies += "com.spotify"   % "missinglink-core" % "0.2.11"

resolvers += Resolver.sonatypeCentralSnapshots
