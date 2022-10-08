import mill._, scalalib._, scalanativelib._, publish._

trait Utest extends TestModule {
  def testFramework = "utest.runner.Framework"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::utest::0.7.11"
  )
}

trait RediclModule extends ScalaModule with PublishModule {
  def scalaVersion = "3.1.2"
  def millSourcePath = super.millSourcePath / os.up
  def ivyDeps = Agg(
    ivy"com.lihaoyi::geny::1.0.0"
  )
  def publishVersion = T.input{
    T.env.get("VERSION").getOrElse(
      os.proc("git", "describe", "--dirty=-SNAPSHOT").call().out.trim
    )
  }
  def pomSettings = PomSettings(
    description = "Redis client.",
    organization = "io.crashbox",
    url = "https://github.com/jodersky/redicl",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("jodersky", "redicl"),
    developers = Seq(
      Developer("jodersky", "Jakob Odersky","https://github.com/jodersky")
    )
  )
  def artifactName = "redicl"
}

object redicl extends Module {
  object jvm extends RediclModule {
    object test extends Tests with Utest
  }
  object native extends RediclModule with ScalaNativeModule {
    object test extends Tests with Utest
    def scalaNativeVersion = "0.4.5"
  }
}
