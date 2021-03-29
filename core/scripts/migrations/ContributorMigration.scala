import cats.data._
import cats.syntax._
import cats.implicits._
import com.amazonaws.services.s3.model._
import com.pennsieve.aws.s3.AWSS3Container
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db._
import com.pennsieve.models.{Contributor, Dataset, DatasetContributor, User}
import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.utilities.{AbstractError, Container}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import ch.qos.logback.classic.{Level, Logger}
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class MigrationContainer(val config: Config) extends Container with DatabaseContainer

class ContributorMigration(
                            postgresUser: String,
                            postgresPassword: String,
                            postgresHost: String,
                            postgresPort: String,
                            environment: String = "dev",
                            dryRun: Boolean = true,
                          ) {

  def await[A](e: Future[A]): A = Await.result(e, Duration.Inf)

  def splitNames(contributor: String): (String,String) = {
    val pattern_first_word = """^[^\s]*""".r
    val pattern_last_words = """ .*$""".r

    if (contributor contains ",") {
      var first_name = pattern_last_words.findFirstIn(contributor.replace(',',
        ' ')).getOrElse("").trim()
      var last_name = pattern_first_word.findFirstIn(contributor.replace(',',
        ' ')).getOrElse("").trim()
      (first_name, last_name)
    } else {
      var first_name = pattern_first_word.findFirstIn(contributor).getOrElse("").trim()
      var last_name = pattern_last_words.findFirstIn(contributor).getOrElse("").trim()
      (first_name, last_name)
    }
  }

  def sortByEmailPrefix(u1: User, u2: User) = {
    val patternEmailPrefix = """^*.@""".r

    patternEmailPrefix.findFirstIn(u1.email) < patternEmailPrefix.findFirstIn(u2.email)
  }

  def maybeMatchContributorToUser(contributorName: (String,String), userList: List[User]): (String, String, Option[Int]) =
    userList.filter { user => user.firstName === contributorName._1 && user.lastName === contributorName._2} match {
        case Nil => (contributorName._1, contributorName._2, None)
        case user => (contributorName._1, contributorName._2, Some(user.sortWith(sortByEmailPrefix).head.id))
  }

  def migrate: Unit = {

    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].setLevel(Level.INFO)

    if(environment != "dev" && environment != "prod") {
      throw new Exception(s"invalid environment $environment, must be one of (dev, prod)")
    }

    val config: Config = ConfigFactory.load()
      .withValue("environment", ConfigValueFactory.fromAnyRef(environment))
      .withValue("postgres.host", ConfigValueFactory.fromAnyRef(postgresHost))
      .withValue("postgres.port", ConfigValueFactory.fromAnyRef(postgresPort))
      .withValue("postgres.database", ConfigValueFactory.fromAnyRef("postgres"))
      .withValue("postgres.user", ConfigValueFactory.fromAnyRef(postgresUser))
      .withValue("postgres.password", ConfigValueFactory.fromAnyRef(postgresPassword))
      .withValue("s3.region", ConfigValueFactory.fromAnyRef("us-east-1"))
      .withValue("s3.host", ConfigValueFactory.fromAnyRef("s3.host"))

    val container = new MigrationContainer(config)

    val organizations  = await(container.db.run(OrganizationsMapper.result))

    val contrib_list = organizations.map {

      // Step 1 - get the list of all contributors for the org

      organization => {

          println(s"Migrating contributors for organization ${organization.id} (${organization.name})")

          val datasetsMapper = new DatasetsMapper(organization)

          val datasetUsersMapper = new DatasetUserMapper(organization)

          val contributorsMapper = new ContributorMapper(organization)

          val datasetContributorMapper = new DatasetContributorMapper(organization)

          val datasets = await(container.db.run(datasetsMapper.result))

          val contrib_list = datasets.toList.map {
            dataset => dataset.contributors
          }.flatten

          val owner_list = datasets.toList.map {
            dataset => await(container.db.run(datasetsMapper.owner(dataset.id)(datasetUsersMapper)))
          }.flatten

          val owner_names_list = owner_list.map(owner => (owner.firstName, owner.lastName))

          //step 2 - split in first_name and last_name
          val contrib_names_list = contrib_list.map(contributor => splitNames(contributor))

          //step 3 - dedupe the list
          val complete_contrib_names_list = contrib_names_list ::: owner_names_list

          val contrib_names_set = complete_contrib_names_list.toSet

          //step 4 - try to find corresponding users from the same org
          val orgUsers = await(container.db.run(OrganizationsMapper.getUsers(organization.id).result)
            .map(_.toList))

          val matchedUser = contrib_names_set.map {
            contributor => maybeMatchContributorToUser(contributor, orgUsers)
          }

          //step 5 - create the contributor objects
        var cnt=0;

        val createdContribs = matchedUser.map {
            cont => {
              cont._3 match {
                case Some(userId) => {
                  if (dryRun){
                    cnt +=1
                    ((cont._1, cont._2) -> cnt)
                  }else{
                  val row = Contributor(None, None, None, None, Some(userId))
                  val createdContributor = (contributorsMapper returning contributorsMapper) += row
                  val contributor = await(container.db.run(createdContributor))
                  ((cont._1, cont._2) -> contributor.id)}
                }
                case _ => {
                  if (dryRun){
                    cnt +=1
                    ((cont._1, cont._2) -> cnt)
                  }else{
                  val row = Contributor(Some(cont._1), Some(cont._2), None, None, None)
                  val createdContributor = (contributorsMapper returning contributorsMapper) += row
                  val contributor = await(container.db.run(createdContributor))
                  ((cont._1, cont._2) -> contributor.id)}
                }
              }
            }
          }.toMap

          //step 6 - for each dataset, match their contributors array of string to the new created contributors in the
          //contributors table and create a dataset_contributor row.

          val contrib_ds_id_list = datasets.toList.map {
            dataset => {
              dataset.contributors.map {
                contrib =>
                (splitNames(contrib), dataset.id)
              }
            }
          }.flatten
          contrib_ds_id_list.foreach(
            contrib => {
              val contribId = createdContribs(contrib._1)
              if (dryRun) {
              println(s"""${contrib} - ${contribId}""")
              } else {
              await(container.db
                .run(
                  datasetContributorMapper
                    .insertOrUpdate(DatasetContributor(contrib._2, contribId))
                )
              )
            }
            }
          )
      }
    }
  }
}
