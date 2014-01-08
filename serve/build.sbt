import sbtassembly.Plugin._
import AssemblyKeys._

version := "0.1.0"

organization := "com.soundcloud"

scalaVersion := "2.11.6"

exportJars := true

jarName in assembly := "spdt-serve.jar"

parallelExecution in Test := false

scalacOptions ++= Seq("-deprecation", "-unchecked", "-optimize")

test in assembly := {}

resolvers ++= Seq(
  "Big Bee Consultants"  at "http://www.bigbeeconsultants.co.uk/repo",
  "Typesafe Repository"  at "http://repo.typesafe.com/typesafe/releases/",
  "Maven Central Server" at "http://repo1.maven.org/maven2",
  "Cloudera"             at "https://repository.cloudera.com/artifactory/cloudera-repos/",
  "OSS Sonatype"         at "https://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies ++= Seq(
  "io.prometheus" % "simpleclient" % "0.0.8",
  "io.prometheus" % "simpleclient_servlet" % "0.0.8")

libraryDependencies ++= Seq(
  "uk.co.bigbeeconsultants" %% "bee-client" % "0.28.0" excludeAll(
    ExclusionRule(organization= "org.scalatest")))

libraryDependencies ++= Seq(
  "org.scalatra" %% "scalatra" % "2.3.0",
  "org.scalatra" %% "scalatra-scalate" % "2.3.0",
  "org.scalatra" %% "scalatra-scalatest" % "2.3.0" % "test",
  "org.scalatra" %% "scalatra-specs2" % "2.3.0" % "test")

libraryDependencies ++= Seq(
  "org.eclipse.jetty" % "jetty-webapp" % "8.1.7.v20120910")

ivyXML :=
  <dependencies>
    <exclude organization="org.mortbay.jetty" module="servlet-api"/>
  </dependencies>

