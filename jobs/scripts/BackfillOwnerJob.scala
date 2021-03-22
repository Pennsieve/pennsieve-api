
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db.{DatasetUserMapper, DatasetsMapper, OrganizationsMapper, PackagesMapper}
import com.blackfynn.models.{Dataset, Organization, Package, User}
import com.blackfynn.core.utilities.PostgresDatabase
import slick.dbio.Effect
import slick.sql.FixedSqlAction

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class BackfillOwnerJob(
  postgresHost: String,
  postgresPort: Int,
  postgresDatabase: String,
  postgresUser: String,
  postgresPassword: String,
  dryRun: Boolean = true) {

  val db: Database = PostgresDatabase(postgresHost, postgresPort, postgresDatabase, postgresUser, postgresPassword).forURL

  def updatePackage(pkgs: PackagesMapper, pkg: Package, ownerId: Int): FixedSqlAction[Int, NoStream, Effect.Write] = {
    val q = for {
      selected <- pkgs.filter(p => p.id === pkg.id && p.ownerId.isEmpty)
    } yield selected.ownerId

    q.update(Some(ownerId))
  }

  def update(org:Organization, pkgs: PackagesMapper, pkgDataset: (Package, Dataset)) = {
    val (pkg, ds) = pkgDataset
    val owner = getOwner(ds,org)

    owner.foreach { user =>
      if (dryRun) {
        println(s"DRYRUN: would update: ${pkg.name} with user ${user.firstName} ${user.lastName}")
      } else {
        println(s"UPDATING: ${pkg.name} with user ${user.firstName} ${user.lastName}")
        Await.result(db.run(updatePackage(pkgs, pkg, user.id)), 10 seconds)
      }
    }
  }

  def getOwner(dataset: Dataset, org:Organization): Option[User] = {

    implicit val datasetUser = new DatasetUserMapper(org)
    Await.result(db.run(new DatasetsMapper(org).owner(dataset.id)), 10 seconds)
  }

  def updateOrganizationFileSizes(organization: Organization): Unit = {
    val packages = new PackagesMapper(organization)
    val datasets = new DatasetsMapper(organization)
    val query = packages.filter(_.ownerId.isEmpty) join datasets on (_.datasetId === _.id)


    val result: Future[Unit] = db.stream(query.result).foreach {
      pkgDataset => update(organization, packages, pkgDataset)
    }

    Await.result(result, 5 minutes)

  }

  def runForAllOrganizations(f: (Organization => Unit)) = {
    val orgs = Await.result(db.run(OrganizationsMapper.result), 5 minutes )
    orgs.foreach {
      org => f(org)
    }
  }

  def run(): Unit = {
    runForAllOrganizations {
      org => updateOrganizationFileSizes(org)
    }
  }
}

