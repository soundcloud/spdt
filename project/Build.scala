import sbt._
import sbtassembly.Plugin._
import AssemblyKeys._
import Keys._
import sbtrelease.ReleasePlugin._

object BuildSettings {

  val buildSettings = Defaults.defaultSettings ++ Seq(
    publishTo <<= version { v =>
      val repo = envGetOrElse("MAVEN_URL", "<MAVEN_URL>/")
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at repo + "snapshots")
      else
        Some("releases" at repo + "releases")
    },
    autoScalaLibrary := false
  )

  lazy val assemblySettings = sbtassembly.Plugin.assemblySettings ++ Seq(
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) => {
        case PathList("javax", "servlet", xs @ _*) => MergeStrategy.first
        case PathList("log4j.properties") => MergeStrategy.rename
        case x if x.endsWith(".html") => MergeStrategy.first
        case x => old(x)
      }
    }
  )

  def envGetOrElse(key: String, default: String) =
    if (System.getenv(key) != null) System.getenv(key) else default
}

object SPDTBuild extends Build {
  import BuildSettings._

  lazy val root = Project(
    "spdt",
    file(".")).aggregate(compute, serve)

  lazy val serve = Project(
    "serve",
    file("serve"),
    settings = buildSettings ++ assemblySettings).dependsOn(compute)
      .settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)

  lazy val compute = Project(
    "compute",
    file("compute"),
    settings = Defaults.defaultSettings ++ releaseSettings ++ assemblySettings)
      .settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)
}
