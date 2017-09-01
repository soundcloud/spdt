def subProject(name: String, maybeMainClass: Option[String] = None): Project = {
  val generalSettings = Defaults.coreDefaultSettings ++ Seq(
    parallelExecution in IntegrationTest := false,
    scalaVersion := "2.12.0",
    mainClass in Compile := maybeMainClass
  )
  Project(id = s"spdt-$name", base = file(name), settings = generalSettings ++ Defaults.itSettings)
    //.configs(IntegrationTest)
}

lazy val root = Project(
  id = "spdt",
  base = file("."),
  settings = Seq(name := "spdt"),
  aggregate = Seq(serve, compute)
)

lazy val compute = subProject("compute", None)

lazy val serve = subProject("serve", None).dependsOn(compute)
