ThisBuild / resolvers ++= Seq(
  "pennsieve-maven-proxy" at "https://nexus.pennsieve.cc/repository/maven-public",
  Resolver.url("pennsieve-ivy-proxy", url("https://nexus.pennsieve.cc/repository/ivy-public/"))( Patterns("[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]") ),
)

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env("PENNSIEVE_NEXUS_USER"),
  sys.env("PENNSIEVE_NEXUS_PW")
)

logLevel := Level.Warn

// Temporarily disable Coursier because parallel builds fail on Jenkins.
// See https://app.clickup.com/t/a8ned9
ThisBuild / useCoursier := false

addSbtPlugin("com.earldouglas" % "xsbt-web-plugin" % "4.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.9.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
