addSbtPlugin("com.github.sbt"                    % "sbt-ci-release"   % "1.5.10")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"       % "0.5.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"        % "1.5.6")
addSbtPlugin("io.github.davidgregory084"         % "sbt-tpolecat"     % "0.4.1")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"     % "2.4.6")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"         % "2.3.3")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"    % "2.0.2")
addSbtPlugin("dev.zio"                           % "zio-sbt-website"  % "0.0.0+86-4319f79f-SNAPSHOT")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.4"

resolvers += Resolver.sonatypeRepo("public")
