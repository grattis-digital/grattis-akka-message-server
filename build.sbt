name := "grattis-akka-message-server"
maintainer := "info@grattis.digital"

version := "0.0.1"

scalaVersion := "3.3.1"

lazy val akkaHttpVersion = "10.5.2"
lazy val akkaVersion = "2.7.0"


libraryDependencies ++= Seq(

  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,

  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  //"net.logstash.logback" % "logstash-logback-encoder" % "7.4",

  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.mockito" % "mockito-core" % "5.5.0" % Test
)


lazy val root = (project in file(".")).enablePlugins(JavaServerAppPackaging)
Revolver.enableDebugging(port = 9999, suspend = false)