package com.blackfynn.publish

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.config.{ Config, ConfigFactory }
import cats.implicits._
import com.blackfynn.utilities.AbstractError
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.blackfynn.aws.s3.S3
import net.ceedubs.ficus.Ficus._

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import akka.dispatch.MessageDispatcher
import cats.data.EitherT
import com.blackfynn.domain.CoreError

case class PublishError(message: String) extends AbstractError {
  final override def getMessage: String = message
}

sealed trait PublishAction
case object PublishAssets extends PublishAction
case object ExportNeptuneGraph extends PublishAction
case object Finalize extends PublishAction

object Main extends App with StrictLogging {

  def getEnv(key: String): Either[PublishError, String] =
    sys.env
      .get(key)
      .map(value => {
        logger.info(s"$key: $value")
        value
      })
      .toRight(PublishError(s"Missing key '$key'"))

  def getPublishAction(cmd: String): Either[PublishError, PublishAction] =
    cmd.trim.toLowerCase() match {
      case "publish-assets" => Right(PublishAssets)
      case "export-neptune-graph" => Right(ExportNeptuneGraph)
      case "finalize" => Right(Finalize)
      case _ => Left(PublishError(s"Not a value command: ${cmd}"))
    }

  try {
    val start: Long = System.currentTimeMillis / 1000

    val config: Config = ConfigFactory.load()

    implicit lazy val system: ActorSystem = ActorSystem("discover-publish")
    implicit lazy val materializer: ActorMaterializer =
      ActorMaterializer()
    implicit lazy val executionContext: ExecutionContext =
      system.dispatcher

    /**
      * Use Pennsieve S3 client wrapper.
      */
    val s3: S3 = {
      val s3Region: Regions =
        config.as[Option[String]]("s3.region") match {
          case Some(region) => Regions.fromName(region)
          case None => Regions.US_EAST_1
        }

      val clientConfig =
        new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")

      new S3(
        AmazonS3ClientBuilder
          .standard()
          .withClientConfiguration(clientConfig)
          .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
          .withRegion(s3Region)
          .build()
      )
    }

    val result: Either[AbstractError, Unit] = for {
      publishAction <- getEnv("PUBLISH_ACTION").flatMap(getPublishAction(_))
      userId <- getEnv("USER_ID").map(_.toInt)
      userFirstName <- getEnv("USER_FIRST_NAME")
      userLastName <- getEnv("USER_LAST_NAME")
      userOrcid <- getEnv("USER_ORCID")
      userNodeId <- getEnv("USER_NODE_ID")
      organizationId <- getEnv("ORGANIZATION_ID").map(_.toInt)
      organizationNodeId <- getEnv("ORGANIZATION_NODE_ID")
      organizationName <- getEnv("ORGANIZATION_NAME")
      datasetId <- getEnv("DATASET_ID").map(_.toInt)
      datasetNodeId <- getEnv("DATASET_NODE_ID")
      publishedDatasetId <- getEnv("PUBLISHED_DATASET_ID").map(_.toInt)
      version <- getEnv("VERSION").map(_.toInt)
      contributors <- getEnv("CONTRIBUTORS")
      collections <- getEnv("COLLECTIONS")
      externalPublications <- getEnv("EXTERNAL_PUBLICATIONS")
      doi <- getEnv("DOI")

      s3Bucket <- getEnv("S3_BUCKET") // either the publish or embargo bucket
      s3Key <- getEnv("S3_PUBLISH_KEY")

      publishContainer = Await.result(
        PublishContainer.secureContainer(
          config = config,
          s3 = s3,
          s3Key = s3Key,
          s3Bucket = s3Bucket,
          doi = doi,
          datasetId = datasetId,
          datasetNodeId = datasetNodeId,
          publishedDatasetId = publishedDatasetId,
          version = version,
          userId = userId,
          userNodeId = userNodeId,
          userFirstName = userFirstName,
          userLastName = userLastName,
          userOrcid = userOrcid,
          organizationId = organizationId,
          organizationNodeId = organizationNodeId,
          organizationName = organizationName,
          contributors = contributors,
          collections = collections,
          externalPublications = externalPublications
        ),
        10 seconds
      )

      action = publishAction match {
        case PublishAssets => Publish.publishAssets(publishContainer)
        case ExportNeptuneGraph =>
          throw new Exception("Neptune no longer exists")
        case Finalize => Publish.finalizeDataset(publishContainer)
      }

      result <- Await.result(action.value, 48.hour)

    } yield result

    result.valueOr(ex => throw ex)

    val end: Long = System.currentTimeMillis / 1000
    val elapsed = end - start
    logger.info(s"Done. $elapsed seconds")

  } catch {
    case ex: Throwable =>
      logger.error("Publish failed", ex)
      sys.exit(1)
  }

  sys.exit(0)
}
