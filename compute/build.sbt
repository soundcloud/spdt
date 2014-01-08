import sbtassembly.Plugin._
import AssemblyKeys._

version := "0.1.0"

organization := "com.soundcloud.spdt"

scalaVersion := "2.11.6"

exportJars := true

jarName in assembly := "spdt-compute.jar"

parallelExecution in Test := false

scalacOptions ++= Seq("-deprecation", "-unchecked", "-optimize")

test in assembly := {}

resolvers ++= Seq(
  "Typesafe Repository"  at "http://repo.typesafe.com/typesafe/releases/",
  "Maven Central Server" at "http://repo1.maven.org/maven2",
  "OSS Sonatype"         at "https://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies ++= Seq(
  "ch.qos.logback"     %  "logback-classic" % "1.1.3",
  "com.typesafe.play"  %% "play-json"       % "2.4.0-M1",
  "org.scalanlp"       %  "breeze_2.10"     % "0.8",
  "org.rogach"         %% "scallop"         % "0.9.5",
  "org.scalatest"      %% "scalatest"       % "2.2.1" % "test")

