val zioSbtVersion = "0.4.0-alpha.27"

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"          % "3.0.2")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"                % "0.5.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"                 % "1.5.18")
addSbtPlugin("org.typelevel"                     % "sbt-tpolecat"              % "0.5.1")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"              % "2.5.2")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"                  % "2.5.2")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"             % "2.1.0")
addSbtPlugin("dev.zio"                           % "zio-sbt-ci"                % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-ecosystem"         % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-website"           % zioSbtVersion)
addSbtPlugin("com.typesafe"                      % "sbt-mima-plugin"           % "1.1.3")
addSbtPlugin("com.github.cb372"                  % "sbt-explicit-dependencies" % "0.3.1")
addSbtPlugin("ch.epfl.scala"                     % "sbt-missinglink"           % "0.3.6")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.7"
libraryDependencies += "com.spotify"   % "missinglink-core" % "0.2.11"

resolvers += Resolver.sonatypeRepo("public")
