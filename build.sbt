def subProject(name: String, maybeMainClass: Option[String] = None): Project = {
  val generalSettings = Defaults.coreDefaultSettings ++ Seq(
    parallelExecution in IntegrationTest := false,
    scalaVersion := "2.12.2",
    mainClass in Compile := maybeMainClass,
    assemblyJarName in assembly := s"spdt-$name.jar",
    test in assembly := false
  )
  Project(id = s"spdt-$name", base = file(name), settings = generalSettings ++ Defaults.itSettings)
}

lazy val root = Project(
  id = "spdt",
  base = file("."),
  settings = Seq(name := "spdt"),
  aggregate = Seq(serve, compute)
)

lazy val compute = subProject("compute", Some("com.soundcloud.spdt.Application"))

lazy val serve = subProject("serve", Some("com.soundcloud.spdt.serve.Application")).dependsOn(compute)
