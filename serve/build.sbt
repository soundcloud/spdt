libraryDependencies ++= Seq(
  "io.prometheus" % "simpleclient" % "0.0.8",
  "io.prometheus" % "simpleclient_servlet" % "0.0.8")

libraryDependencies ++= Seq(
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.scalatra" %% "scalatra" % "2.5.1",
  "org.scalatra" %% "scalatra-scalate" % "2.5.1",
  "org.scalatra" %% "scalatra-scalatest" % "2.5.1" % "test",
  "org.scalatra" %% "scalatra-specs2" % "2.5.1" % "test")

libraryDependencies ++= Seq(
  "org.eclipse.jetty" % "jetty-webapp" % "8.1.7.v20120910")

ivyXML :=
  <dependencies>
    <exclude organization="org.mortbay.jetty" module="servlet-api"/>
  </dependencies>

