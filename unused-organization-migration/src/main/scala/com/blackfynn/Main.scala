package com.pennsieve.utilities.`unused-organization-migration`

import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.db.{
  CustomTermsOfServiceMapper,
  DatasetsMapper,
  FeatureFlagsMapper,
  OrganizationStorageMapper,
  OrganizationTeamMapper,
  OrganizationUserMapper,
  OrganizationsMapper,
  SubscriptionsMapper,
  TokensMapper,
  UserInvitesMapper,
  UserMapper
}
import com.pennsieve.models.Organization
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Try

class UnusedOrganizationMigrationContainer(
  val config: Config,
  val dryRun: Boolean
) extends Container
    with DatabaseContainer {

  def await[A](e: Future[A]): A = Await.result(e, 1.hour)

  def scanAndDeleteAll(): Unit =
    await {
      db.run(OrganizationsMapper.sortBy(_.id).result).map {
        organizations: Seq[Organization] =>
          {
            organizations.foreach { org: Organization =>
              {
                scanAndDelete(org.id)
                println()
              }
            }
          }
      }
    }

  private def deleteOrganization(organization: Organization): Future[Int] = {

    val orgFeatureFlags: DBIO[Int] =
      FeatureFlagsMapper.filter(_.organizationId === organization.id).delete

    val orgStorage: DBIO[Int] =
      OrganizationStorageMapper
        .filter(_.organizationId === organization.id)
        .delete

    val orgSubscriptions: DBIO[Int] =
      SubscriptionsMapper.filter(_.organizationId === organization.id).delete

    val orgTeams: DBIO[Int] =
      OrganizationTeamMapper.filter(_.organizationId === organization.id).delete

    val orgToS: DBIO[Int] =
      CustomTermsOfServiceMapper
        .filter(_.organizationId === organization.id)
        .delete

    val orgTokens: DBIO[Int] =
      TokensMapper.filter(_.organizationId === organization.id).delete

    val orgUsers: DBIO[Int] =
      OrganizationUserMapper.filter(_.organizationId === organization.id).delete

    val orgUserInvites: DBIO[Int] = {
      UserInvitesMapper.filter(_.organizationId === organization.id).delete
    }

    val deleteOrg: DBIO[Int] =
      OrganizationsMapper.filter(_.id === organization.id).delete

    // Remove the org for any users that have `organization` as the preferred org:
    val updateUserPreferredOrg = (for {
      u <- UserMapper if u.preferredOrganizationId === organization.id
    } yield u.preferredOrganizationId).update(None)

    val getTableNames: Future[Seq[String]] =
      db.run(
        sql"""SELECT tablename FROM pg_tables WHERE schemaname = '#${organization.schemaId}' AND tablename <> 'schema_version' """
          .as[String]
          .map(_.to[Seq])
      )

    for {
      tableNames <- getTableNames
      q = for {
        c1 <- orgFeatureFlags
        c2 <- orgStorage
        c3 <- orgSubscriptions
        c4 <- orgTeams
        c5 <- orgTokens
        c6 <- orgToS
        c7 <- orgUsers
        c8 <- orgUserInvites
        c9 <- updateUserPreferredOrg
        c10 <- deleteOrg
        _ <- DBIO.seq(tableNames.map((table: String) => {
          println(
            s"TRUNCATE TABLE '${organization.schemaId}'.'${table}' RESTART IDENTITY CASCADE"
          )
          sqlu""" TRUNCATE TABLE "#${organization.schemaId}"."#${table}" RESTART IDENTITY CASCADE"""
        }): _*)
      } yield c1 + c2 + c3 + c4 + c5 + c6 + c7 + c8 + c9 + c10
      count <- db.run(q.transactionally)
    } yield count
  }

  private def scanAndDelete(organization: Organization): Future[Unit] = {
    val datasetMapper = new DatasetsMapper(organization)
    val hasNoDatasets: Future[Option[String]] =
      db.run(datasetMapper.exists.result).map { hasDatasets =>
        hasDatasets match {
          case true => None
          case _ => Some("reason: no datasets")
        }
      }
    val shouldDelete: Future[Option[String]] =
      hasNoDatasets.map {
        case reason @ Some(_) => reason
        case _ => None
      }
    shouldDelete.flatMap {
      case (Some(reason)) => {
        println(
          s"${organization.id} | ${organization.nodeId} - '${organization.name}' => DELETING (${reason})"
        )
        if (dryRun) {
          Future.successful(())
        } else {
          deleteOrganization(organization).map { count =>
            {
              println(
                s"${organization.id} | ${organization.nodeId} - '${organization.name} => ${count} row(s) deleted"
              )
            }
          }
        }
      }
      case _ => {
        println(
          s"${organization.id} | ${organization.nodeId} - '${organization.name}' => SKIPPING"
        )
        Future.successful(())
      }
    }
  }

  def scanAndDelete(organizationId: Int): Unit = {
    await {
      db.run(OrganizationsMapper.getOrganization(organizationId))
        .flatMap(scanAndDelete(_))
    }
  }
}

object Main extends App {

  val config: Config = ConfigFactory.load()
  val environment: String = config.as[String]("environment")
  val organization: String = config.as[String]("organization")
  val organizationId: Option[Int] = Try(organization.toInt).toOption
  val dryRun = config.as[Boolean]("dry_run")

  println(s"- ENVIRONMENT = ${environment}")
  println(s"- ORGANIZATION = ${organization}")
  println(s"- DRY RUN = ${dryRun}")

  val migration =
    new UnusedOrganizationMigrationContainer(config, dryRun = dryRun)
  organizationId match {
    case Some(orgId: Int) => {
      migration.scanAndDelete(orgId)
    }
    case None => migration.scanAndDeleteAll()
  }
}
