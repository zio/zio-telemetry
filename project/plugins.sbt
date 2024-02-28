val zioSbtVersion = "0.4.0-alpha.23"

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"  % "3.0.2")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"        % "0.5.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"         % "1.5.15")
addSbtPlugin("org.typelevel"                     % "sbt-tpolecat"      % "0.5.0")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"      % "2.5.2")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"          % "2.5.2")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"     % "2.0.11")
addSbtPlugin("dev.zio"                           % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-ecosystem" % zioSbtVersion)
addSbtPlugin("dev.zio"                           % "zio-sbt-website"   % zioSbtVersion)

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.7"

resolvers += Resolver.sonatypeRepo("public")
