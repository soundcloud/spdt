resolvers ++= Seq(
  "Typesafe Repository"  at "http://repo.typesafe.com/typesafe/releases/",
  "Maven Central Server" at "http://repo1.maven.org/maven2",
  "OSS Sonatype"         at "https://oss.sonatype.org/content/repositories/snapshots",
  "artifactory"          at "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"
)


addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.5")
