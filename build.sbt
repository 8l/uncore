organization := "edu.berkeley.cs"

version := "2.0"

name := "uncore"

scalaVersion := "2.10.2"

resolvers ++= Seq(
  "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases",
  "scct-github-repository" at "http://mtkopone.github.com/scct/maven-repo"
)

libraryDependencies += "edu.berkeley.cs" %% "chisel" % "2.1-SNAPSHOT"
