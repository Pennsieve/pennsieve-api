package com.pennsieve.publish

import com.pennsieve.aws.s3.S3
import com.pennsieve.core.utilities.{
  DatabaseContainer,
  DatasetManagerContainer,
  DatasetMapperContainer,
  DatasetRoleContainer,
  OrganizationContainer,
  PackagesMapperContainer,
  UserPermissionContainer
}
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.managers.{ FileManager, PackageManager }
import com.pennsieve.models._
import com.pennsieve.utilities.Container
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.apache.commons.lang3.StringUtils
import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.core.utilities.DatasetAssetsContainer
import io.circe.parser.decode

case class InsecureDBContainer(config: Config, organization: Organization)
    extends Container
    with DatabaseContainer
    with DatasetMapperContainer
    with OrganizationContainer {
  // Discover-publish connects to a local database which does not
  // support SSL
  override val postgresUseSSL = false
}

case class PublishContainer(
  config: Config,
  s3: S3,
  s3Bucket: String,
  s3AssetBucket: String,
  s3Key: String,
  s3AssetKeyPrefix: String,
  s3CopyChunkSize: Int,
  s3CopyChunkParallelism: Int,
  s3CopyFileParallelism: Int,
  doi: String,
  dataset: Dataset,
  publishedDatasetId: Int,
  version: Int,
  organization: Organization,
  user: User,
  userOrcid: String,
  datasetRole: Option[Role],
  contributors: List[PublishedContributor],
  collections: List[PublishedCollection],
  externalPublications: List[PublishedExternalPublication]
) extends Container
    with OrganizationContainer
    with PackagesMapperContainer
    with DatasetManagerContainer
    with DatasetAssetsContainer
    with UserPermissionContainer
    with DatasetRoleContainer {

  lazy val packageManager: PackageManager = new PackageManager(datasetManager)

  lazy val fileManager: FileManager =
    new FileManager(packageManager, organization)

  // Discover-publish connects to a local database which does not
  // support SSL
  override val postgresUseSSL = false
}

object PublishContainer {

  def secureContainer(
    config: Config,
    s3: S3,
    s3Key: String,
    s3Bucket: String,
    doi: String,
    datasetId: Int,
    datasetNodeId: String,
    publishedDatasetId: Int,
    version: Int,
    userId: Int,
    userNodeId: String,
    userFirstName: String,
    userLastName: String,
    userOrcid: String,
    organizationId: Int,
    organizationNodeId: String,
    organizationName: String,
    contributors: String,
    collections: String,
    externalPublications: String
  )(implicit
    executionContext: ExecutionContext
  ): Future[PublishContainer] = {

    // Because the publish job operates on a single schema of the database, it does
    // not have access to the information needed to build a full Organization object
    val organization =
      Organization(
        nodeId = organizationNodeId,
        name = organizationName,
        "",
        id = organizationId
      )

    // Nor a full User object
    val user =
      User(
        nodeId = userNodeId,
        email = "",
        firstName = userFirstName,
        middleInitial = None,
        lastName = userLastName,
        degree = None,
        password = "",
        id = userId
      )

    val insecureContainer = InsecureDBContainer(config, organization)

    for {
      dataset <- insecureContainer.db.run(
        insecureContainer.datasetsMapper.getDataset(datasetId)
      )

      publishedContributors <- decode[List[PublishedContributor]](contributors)
        .fold(Future.failed, Future.successful)

      publishedCollections <- decode[List[PublishedCollection]](collections)
        .fold(Future.failed, Future.successful)

      publishedExternalPublications <- decode[List[
        PublishedExternalPublication
      ]](externalPublications)
        .fold(Future.failed, Future.successful)

    } yield
      PublishContainer(
        config = config,
        s3 = s3,
        s3Bucket = s3Bucket,
        s3AssetBucket = config.as[String]("s3.asset-bucket"),
        // Ensure all S3 keys have a trailing slash
        s3Key = StringUtils.appendIfMissing(s3Key, "/"),
        s3AssetKeyPrefix = config.as[String]("s3.asset-key-prefix"),
        s3CopyChunkSize = config.as[Int]("s3.copy-chunk-size"),
        s3CopyChunkParallelism = config.as[Int]("s3.copy-chunk-parallelism"),
        s3CopyFileParallelism = config.as[Int]("s3.copy-file-parallelism"),
        doi = doi,
        dataset = dataset,
        publishedDatasetId = publishedDatasetId,
        version = version,
        organization = organization,
        user = user,
        userOrcid = userOrcid,
        datasetRole = Some(Role.Owner),
        contributors = publishedContributors,
        collections = publishedCollections,
        externalPublications = publishedExternalPublications
      )
  }
}
