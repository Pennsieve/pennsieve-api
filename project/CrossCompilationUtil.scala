import sbt.{CrossVersion, ModuleID}

object CrossCompilationUtil {

  def scalaVersionMatch[T](
    scalaVersion: String,
    if212: T,
    otherwise: T
  ): T = {
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, 12)) => if212
      case _ => otherwise
    }
  }

  def getVersion(
    scalaVersion: String,
    depVersionFor212: String,
    depVersionFor213: String
  ): String = {
    scalaVersionMatch(scalaVersion, depVersionFor212, depVersionFor213)
  }

  def handle212OnlyDependency(
    scalaVersion: String,
    moduleIdFor212: ModuleID
  ): Seq[ModuleID] = {
    scalaVersionMatch(scalaVersion, Seq(moduleIdFor212), Nil)
  }

}
