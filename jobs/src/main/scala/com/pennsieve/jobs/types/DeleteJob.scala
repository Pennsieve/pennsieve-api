/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.jobs.types

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{
  Broadcast,
  Concat,
  Flow,
  GraphDSL,
  Merge,
  Sink,
  Source
}
import akka.stream.FlowShape
import cats.FlatMap
import cats.data.EitherT
import cats.implicits._
import com.amazonaws.ClientConfiguration
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.pennsieve.audit.middleware.{ AuditLogger, Auditor, GatewayHost }
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.aws.s3._
import com.pennsieve.clients
import com.pennsieve.clients._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities._
import com.pennsieve.db.{ DatasetAssetsMapper, _ }
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.jobs.contexts.{
  CatalogDeleteContext,
  DatasetDeleteContext,
  PackageDeleteContext
}
import com.pennsieve.jobs.{ DeleteResult, _ }
import com.pennsieve.managers._
import com.pennsieve.messages._
import com.pennsieve.models.FileType.Aperio
import com.pennsieve.models.PackageType.{ Collection, TimeSeries }
import com.pennsieve.models.{
  Channel,
  DatasetState,
  File,
  Organization,
  Package,
  PackageState
}
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import com.pennsieve.streaming.RangeLookUp
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.traits.TimeSeriesDBContainer
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import scalikejdbc.{ AutoSession, DBSession }

import java.time.Instant
import java.time.temporal.ChronoField
import scala.jdk.CollectionConverters._
import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class AWSContainer(
  config: Config
)(implicit
  ec: ExecutionContext,
  system: ActorSystem
) extends InsecureContainer(config)
    with InsecureCoreContainer
    with DataDBContainer
    with TimeSeriesDBContainer

class LocalContainer(
  config: Config
)(implicit
  ec: ExecutionContext,
  system: ActorSystem
) extends InsecureContainer(config)
    with InsecureCoreContainer
    with DatabaseContainer
    with DataDBContainer
    with TimeSeriesDBContainer

trait CreditDeleteJob {
  def creditDeleteJob(
    job: CatalogDeleteJob
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit]
  def deleteDatasetJob(
    job: DeleteDatasetJob
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit]

  def record(job: CatalogDeleteJob, result: DeleteResult): Future[Unit]
}

object DeleteJob {

  type Container = InsecureContainer
    with CoreContainer
    with DatabaseContainer
    with DataDBContainer
    with TimeSeriesDBContainer

  type ModelServiceClient = ModelServiceV2Client

  def apply(
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext,
    log: ContextLogger
  ): DeleteJob = {
    val config: Config = ConfigFactory.load()
    val environment: String = config.as[String]("environment")
    val container: Container =
      if (environment == "local") new LocalContainer(config)
      else new AWSContainer(config)

    implicit val jwt: Jwt.Config = new Jwt.Config {
      override def key: String = config.as[String]("jwt.key")
    }

    val endpoint: Option[String] = config.getAs[String]("s3.endpoint")
    val region: String = config.getOrElse[String]("s3.region", "us-east-1")
    val pathStyleAccess: Boolean =
      config.getOrElse[Boolean]("s3.path_style_access", false)

    val s3ClientBuilder: AmazonS3ClientBuilder = {
      val clientConfig =
        new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
      val builder = AmazonS3ClientBuilder
        .standard()
        .withPathStyleAccessEnabled(pathStyleAccess)
        .withClientConfiguration(clientConfig)
      endpoint match {
        case Some(ep) =>
          val endpointConfig = new EndpointConfiguration(s"$ep", region)
          builder.withEndpointConfiguration(endpointConfig)
        case _ => builder
      }
    }
    val s3Client: AmazonS3 = s3ClientBuilder.build()

    val datasetAssetClient = new S3DatasetAssetClient(
      new S3(s3Client),
      config.as[String]("s3.dataset_asset_bucket")
    )

    val auditLogger: Auditor = {
      val host = config.as[String]("gateway.host")
      new AuditLogger(GatewayHost(host))
    }

    val modelServiceClient: ModelServiceClient = {

      /**
        * By default, the akka-http server closes idle connections after 60
        * seconds. This configures the client to preemptively close idle
        * connections before then to mitigate "Connection reset" errors.
        */
      val client = HttpClients
        .custom()
        .setConnectionManager(new PoolingHttpClientConnectionManager())
        .evictExpiredConnections()
        .evictIdleConnections(30, TimeUnit.SECONDS)
        .build()

      new clients.ModelServiceClient(
        client,
        Settings.modelServiceHost,
        Settings.modelServicePort
      )
    }

    val rangeLookup = new RangeLookUp("", "")

    val timeSeriesBucket = config.as[String]("timeseries.bucket")

    new DeleteJob(
      db = container.db,
      amazonS3 = s3Client,
      rangeLookup = rangeLookup,
      timeSeriesBucketName = timeSeriesBucket,
      auditLogger = auditLogger,
      datasetAssetClient = datasetAssetClient,
      modelServiceClient = modelServiceClient,
      container
    )
  }

}

case class S3Object(bucket: String, key: String)

class DeleteJob(
  db: Database,
  amazonS3: AmazonS3,
  rangeLookup: RangeLookUp,
  timeSeriesBucketName: String,
  auditLogger: Auditor,
  datasetAssetClient: DatasetAssetClient,
  modelServiceClient: DeleteJob.ModelServiceClient,
  container: DeleteJob.Container
)(implicit
  ec: ExecutionContext,
  log: ContextLogger,
  jwt: Jwt.Config
) extends CreditDeleteJob {

  implicit val tier: Tier[DeleteJob] = Tier[DeleteJob]

  // var so we can override in tests
  implicit var autoSession: DBSession = AutoSession

  def getIdsToDelete(
    packageTable: PackagesMapper
  ): Flow[CatalogDeleteJob, Either[CoreError, Set[Int]], NotUsed] =
    Flow[CatalogDeleteJob].mapAsync(1) {
      case DeletePackageJob(packageId, organizationId, userId, traceId, _) =>
        log.tierContext.info(s"Attempting to delete package")(
          PackageDeleteContext(
            organizationId = organizationId,
            userId = userId,
            packageId = Some(packageId),
            traceId = traceId
          )
        )

        val result = for {
          pkg <- db
            .run(
              packageTable
                .filter(_.id === packageId)
                .filter(_.state === (PackageState.DELETING: PackageState))
                .result
                .headOption
            )
            .whenNone[CoreError](NotFound(s"Package ($packageId)"))

          descendants <- packageTable.descendants(pkg, db).toEitherT
        } yield descendants + pkg.id

        result.value
      case DeleteDatasetJob(datasetId, organizationId, userId, traceId, _) =>
        log.tierContext.info(s"Attempting to delete dataset")(
          DatasetDeleteContext(
            organizationId = organizationId,
            userId = userId,
            datasetId = Some(datasetId),
            traceId = traceId
          )
        )

        db.run(packageTable.filter(_.datasetId === datasetId).map(_.id).result)
          .map(_.toSet.asRight)

    }

  def record(job: CatalogDeleteJob, result: DeleteResult): Future[Unit] = {
    implicit val context: LogContext =
      CatalogDeleteContext(job.organizationId, job.userId)

    if (result.success) {
      log.tierContext.info(result.message)
      auditLogger
        .message()
        .append("job-name", s"delete-package")
        .append("job-id", job.id)
        .append("user-id", job.userId)
        .append("delete-resource-ids", result.deletedResourceIds.toList: _*)
        .log(job.traceId)
    } else {
      val infoMap = result.toMap
        .updated("userId", job.userId)
        .updated("jobId", job.id)
        .updated("jobName", "delete-package")
        .updated("timestamp", Instant.now.get(ChronoField.MICRO_OF_SECOND))

      log.tierContext.error(infoMap.toString)
      Future.successful(())
    }
  }

  val concatIds: Flow[Either[CoreError, Set[Int]], Int, NotUsed] =
    Flow[Either[CoreError, Set[Int]]].mapConcat {
      case Left(error) =>
        log.tierNoContext.error(
          s"delete job errored while collecting ids for removal: $error"
        )
        Set.empty[Int]
      case Right(descendantIds) =>
        log.tierNoContext.info(s"Processing ${descendantIds.size} ids")
        descendantIds
    }

  def getPackage(
    packageTable: PackagesMapper,
    organization: Organization
  ): Flow[Int, Package, NotUsed] =
    Flow[Int]
      .mapAsync(1) { id =>
        db.run(packageTable.filter(_.id === id).result.headOption)
      }
      .filter(_.isDefined)
      .map(_.get)

  // this is a check to make sure all package children were deleted. if there are any children return an error to be
  // logged
  def deleteChildren(
    packageTable: PackagesMapper
  ): Flow[Package, DeleteResult, NotUsed] =
    Flow[Package]
      .filterNot { pkg =>
        pkg.`type` == Collection
      }
      .mapAsync[Seq[Package]](1) { pkg =>
        val query = packageTable.filter(packageTable.hasParent(_, Some(pkg)))
        db.run(query.result)
      }
      .mapConcat(_.toList)
      .map { child =>
        InvalidChildDeleteResult(child.nodeId)
      }

//  val allFilesQuery = new TableQuery(new AllFilesView(_))

  def deletePackageFiles(
    filesTable: FilesMapper
  ): Flow[Package, DeleteResult, NotUsed] =
    Flow[Package]
      .mapAsync(1) { pkg: Package =>
        db.run(filesTable.filter(_.packageId === pkg.id).result)
          .map(_.toList)
      }
      .mapConcat(identity)
      // if any files exist with the same s3 bucket/path do nothing
      // else delete s3 assets
//      .mapAsync(1) { file =>
//        val query = allFilesQuery
//          .filter(t => t.s3bucket === file.s3Bucket && t.s3key === file.s3Key)
//          .filter(
//            t =>
//              t.id =!= file.id && t.packageId =!= file.packageId && t.organizationId =!= filesTable.organization.id
//          )
//          .exists
//        db.run(query.result)
//          .map(keyExists => (keyExists, file))
//      }
//      .filterNot(_._1)
      .flatMapConcat {
        case (file: File) =>
          Source.single(file).via(deleteFileS3Assets)
      }

  def deletePackageFlow(
    packageTable: PackagesMapper
  ): Flow[Package, DeleteResult, NotUsed] = {
    val filesTable = new FilesMapper(packageTable.organization)
    val channelTable = new ChannelsMapper(packageTable.organization)

    Flow[Package].flatMapConcat { pkg =>
      val deleteChildrenSource =
        Source.single(pkg).via(deleteChildren(packageTable))
      val deleteChannelsSource = Source
        .single(pkg)
        .filter(_.`type` == TimeSeries)
        .via(deleteChannels(channelTable))
      val deletePackageFilesSource =
        Source.single(pkg).via(deletePackageFiles(filesTable))

      // TODO: delete package from `model-service`

      Source.combine(
        deleteChildrenSource,
        deleteChannelsSource,
        deletePackageFilesSource
      )(Concat.apply(_))
    }
  }

  def getObjectIdsForDirectory(
    bucket: String,
    directoryId: String
  )(implicit
    amazonS3: AmazonS3
  ): List[String] = {
    def getNextList(listing: ObjectListing): Seq[S3ObjectSummary] =
      if (listing.isTruncated)
        listing.getObjectSummaries.asScala.toSeq ++ getNextList(
          amazonS3.listNextBatchOfObjects(listing)
        )
      else listing.getObjectSummaries.asScala.toSeq

    getNextList(amazonS3.listObjects(bucket, directoryId))
      .map(_.getKey)
      .toList
  }

  def deleteAllVersions(bucket: String, key: String, client: AmazonS3) = {
    // Gather all versions and hard delete them
    val versionListing: VersionListing = client.listVersions(bucket, key)
    val deleteRequests = versionListing.getVersionSummaries.asScala.map { versionSummary =>
      new DeleteVersionRequest(bucket, key, versionSummary.getVersionId)
    }
    deleteRequests.foreach(request => client.deleteVersion(request))
  }

  val deleteS3Objects: Flow[S3Object, S3DeleteResult, NotUsed] =
    Flow[S3Object]
      .groupBy(2, _.bucket)
      .grouped(1000)
      .map { s3Ids =>
        val s3Keys = s3Ids.map(_.key)
        val bucket = s3Ids.head.bucket
        log.tierNoContext.info(
          s"Deleting ${s3Ids.size} keys from bucket: $bucket"
        )
        val req = new DeleteObjectsRequest(bucket)
          .withKeys(s3Keys: _*)
        try {
          val deletedIds = amazonS3
            .deleteObjects(req)
            .getDeletedObjects
            .asScala
            .map(_.getKey)
          S3DeleteResult(
            bucket = bucket,
            deletedKeys = deletedIds.toSeq,
            notDeletedKeys = Seq.empty
          )
        } catch {
          case ex: MultiObjectDeleteException =>
            val deletedIds = ex.getDeletedObjects.asScala
              .map(_.getKey)
            S3DeleteResult(
              bucket = bucket,
              deletedKeys = deletedIds.toSeq,
              notDeletedKeys = ex.getErrors.asScala.toSeq
            )
        }
      }
      .mergeSubstreams

  val deleteFileS3Assets: Flow[File, S3DeleteResult, NotUsed] =
    Flow[File]
      .mapConcat { file =>
        // get s3 delete ids
        if (file.fileType == Aperio) {
          getObjectIdsForDirectory(file.s3Bucket, file.s3Key)(amazonS3)
            .map(key => S3Object(file.s3Bucket, key))
        } else List(S3Object(file.s3Bucket, file.s3Key))
      }
      .via(deleteS3Objects)

  def dualFlow[In, Out, M1, M2, Mat](
    flow1: Flow[In, Out, M1],
    flow2: Flow[In, Out, M2]
  )(
    combineMat: (M1, M2) => Mat
  ): Flow[In, Out, Mat] = Flow.fromGraph {
    GraphDSL.createGraph(flow1, flow2)(combineMat) {
      implicit builder => (f1, f2) =>
        import GraphDSL.Implicits._
        val bcast = builder.add(Broadcast[In](2))
        val merge = builder.add(Merge[Out](2))

        bcast ~> f1 ~> merge
        bcast ~> f2 ~> merge

        FlowShape(bcast.in, merge.out)
    }
  }

  def deleteChannels(
    channelTable: ChannelsMapper
  ): Flow[Package, DeleteResult, NotUsed] =
    Flow[Package]
      .filter { pkg =>
        pkg.`type` == TimeSeries
      }
      .mapAsync(1) { pkg =>
        db.run(
            channelTable
              .filter(_.packageId === pkg.id)
              .result
          )
          .map(_.toList)
      }
      .mapConcat(identity)
      .flatMapConcat { channel =>
        Source.single(channel).via(deleteChannel)
      }

  val deleteChannel: Flow[Channel, DeleteResult, NotUsed] =
    Flow[Channel].flatMapConcat { channel =>
      Try(rangeLookup.get(channel.nodeId)) match {
        case Success(lookups) =>
          val s3Deletes = Source(lookups)
            .map(lookup => S3Object(timeSeriesBucketName, lookup.file))
            .via(deleteS3Objects)

          val rangesDelete = Source
            .single(rangeLookup.deleteRangeLookups(channel.nodeId))
            .map(
              deletedRows =>
                RangeLookUpResult(channel.nodeId, lookups, deletedRows)
            )

          Source.combine(s3Deletes, rangesDelete)(Concat.apply(_))
        case Failure(error) =>
          log.tierNoContext.error(error.getMessage)
          Source.single(ThrowableResult(error.getMessage))
      }
    }

  def deleteTimeSeriesPackage(
    packageTable: PackagesMapper
  ): Flow[Package, DeleteResult, NotUsed] =
    Flow[Package].flatMapConcat { pkg =>
      val channelIdsQuery = container.timeSeriesAnnotationManager.findBaseQuery
        .filter(_._1.timeSeriesId === pkg.nodeId)
        .map(_._2.channelIds)
        .result
        .map(_.foldLeft(SortedSet.empty[String]) { (accu, ids) =>
          accu ++ ids
        })
      val timeseriesDeleteResults = for {
        channelIds <- container.dataDB.run(channelIdsQuery)
        annotationDeleteResult <- container.timeSeriesAnnotationManager
          .delete(pkg.nodeId)
          .map { rowsDeleted =>
            TimeSeriesSQLDeleteResult(
              tableName =
                container.timeSeriesAnnotationTableQuery.baseTableRow.tableName,
              rowsDeleted = rowsDeleted,
              timeSeriesId = pkg.nodeId
            )
          }
          .recover {
            case err => ThrowableResult(err.getMessage)
          }
        channelGroupDeleteResult <- container.channelGroupManager
          .deleteBy(channelIds)
          .map { rowsDeleted =>
            TimeSeriesSQLDeleteResult(
              tableName =
                container.channelGroupTableQuery.baseTableRow.tableName,
              rowsDeleted = rowsDeleted,
              timeSeriesId = pkg.nodeId
            )
          }
          .recover {
            case err => ThrowableResult(err.getMessage)
          }
        layerDeleteResult <- container.layerManager
          .delete(pkg.nodeId)
          .map { deletedRows =>
            TimeSeriesSQLDeleteResult(
              container.layerTableQuery.baseTableRow.tableName,
              deletedRows,
              pkg.nodeId
            )
          }
          .recover {
            case err => ThrowableResult(err.getMessage)
          }
      } yield
        List(
          annotationDeleteResult,
          channelGroupDeleteResult,
          layerDeleteResult
        )

      val deletePackage = Source
        .single(pkg)
        .via(deletePackageFlow(packageTable))

      val timeSeriesDelete =
        Source.future(timeseriesDeleteResults).mapConcat(identity)

      Source.combine(timeSeriesDelete, deletePackage)(Concat.apply(_))
    }

  def deleteFlow(
    packageTable: PackagesMapper
  ): Flow[Package, DeleteResult, NotUsed] =
    Flow[Package]
      .flatMapConcat {
        case pkg =>
          pkg.`type` match {
            case TimeSeries =>
              Source
                .single(pkg)
                .via(deleteTimeSeriesPackage(packageTable))
            case _ =>
              Source
                .single(pkg)
                .via(deletePackageFlow(packageTable))
          }
      }

  def deleteDatasetFromModelService(
    job: DeleteDatasetJob
  ): Future[DatasetDeletionSummary] = {

    def request(): Future[DatasetDeletionSummary] = {
      val token: Jwt.Token =
        JwtAuthenticator.generateServiceToken(
          duration = 5.seconds,
          organizationId = job.organizationId,
          datasetId = Some(job.datasetId)
        )
      Future {
        log.noContext.info(
          s"REQUEST - Delete dataset model-service :: job=${job}"
        )
        modelServiceClient.deleteDataset(
          token,
          job.organizationId,
          job.datasetId
        )
      }.flatMap(_.fold(Future.failed, Future.successful))
    }

    val maxSequentialFailures: Int = 5

    val init: (Int, Int, DatasetDeletionSummary) =
      (0, 0, DatasetDeletionSummary.empty)

    FlatMap[Future]
      .tailRecM[
        (Int, Int, DatasetDeletionSummary),
        (Int, Int, DatasetDeletionSummary)
      ](init) {
        case (
            iteration: Int,
            sequentialFailures: Int,
            totalCountSummary: DatasetDeletionSummary
            ) => {
          request()
            .flatMap { summary =>
              {
                val updatedCounts: DatasetDeletionCounts =
                  totalCountSummary.counts + summary.counts
                summary.done match {
                  case true => {
                    log.noContext.info(
                      s"SUCCESS - Delete dataset (model-service) done :: job=${job}"
                    )
                    Future.successful(
                      Right(
                        (
                          iteration + 1,
                          0,
                          DatasetDeletionSummary(
                            done = true,
                            counts = updatedCounts
                          )
                        )
                      )
                    )
                  }
                  case _ => {
                    log.noContext.info(
                      s"CONTINUE($iteration) - Delete dataset (model-service) :: job=${job}"
                    )
                    Future.successful(
                      Left(
                        (
                          iteration + 1,
                          0,
                          DatasetDeletionSummary(
                            done = false,
                            counts = updatedCounts
                          )
                        )
                      )
                    )
                  }
                }
              }
            }
            .recoverWith {
              case e: Exception => {
                if (sequentialFailures >= maxSequentialFailures) {
                  log.noContext.error(
                    s"FAILED - Delete dataset (model-service) :: job=${job}\n${e}"
                  )
                  Future.failed(e)
                } else {
                  log.noContext.error(
                    s"FAILURE($sequentialFailures) - Delete dataset (model-service) :: job=${job}\n${e}"
                  )

                  Thread.sleep(2000) // chill for 2s before retrying

                  Future.successful(
                    Left(iteration, sequentialFailures + 1, totalCountSummary)
                  )
                }
              }
            }
        }
      }
      .map(_._3)
  }

  def deletePackageFromPostgres(
    job: CatalogDeleteJob,
    packageTable: PackagesMapper
  ): Future[DeleteResult] =
    job match {
      case DeletePackageJob(packageId, _, _, traceId, _) =>
        db.run(
            packageTable
              .filter(_.id === packageId)
              .map(_.state)
              .update(PackageState.DELETED)
          )
          .map {
            case 1 =>
              PackageDeleteResult(
                success = true,
                message =
                  s"Successfully deleted package ($packageId) from Postgres",
                packageNodeId = "no node ID available",
                traceId = traceId
              )
            case _ =>
              PackageDeleteResult(
                success = false,
                message = s"Failed to delete package ($packageId) from Postgres",
                packageNodeId = "no node ID available",
                traceId = traceId
              )
          }
      case _: DeleteDatasetJob => Future.successful(NoOpDeleteResult)
    }

  def deleteJobFlow(
    packageTable: PackagesMapper,
    organization: Organization
  ): Flow[CatalogDeleteJob, (CatalogDeleteJob, DeleteResult), NotUsed] =
    Flow[CatalogDeleteJob].flatMapConcat { job: CatalogDeleteJob =>
      Source
        .single(job)
        .via(getIdsToDelete(packageTable))
        .via(concatIds)
        .via(getPackage(packageTable, organization))
        .via(deleteFlow(packageTable))
        .map(r => (job, r))
    }

  def creditDeleteJob(
    job: CatalogDeleteJob
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit] = {
    for {
      organization <- db
        .run(OrganizationsMapper.get(job.organizationId))
        .map(_.toRight[JobException](InvalidOrganization(job.organizationId)))
        .toEitherT(ExceptionError)

      packageTable = new PackagesMapper(organization)

      result <- Source
        .single(job)
        .via(deleteJobFlow(packageTable, organization))
        .mapAsync(1) {
          case (deleteJob: CatalogDeleteJob, deleteResult: DeleteResult) =>
            record(deleteJob, deleteResult)
        }
        .runWith(Sink.ignore)
        .flatMap(_ => deletePackageFromPostgres(job, packageTable))
        .map(result => Right(record(job, result)): Either[JobException, Unit])
        .toEitherT(ExceptionError)
    } yield result
  }

  def deleteDataset(
    job: DeleteDatasetJob,
    datasetTable: DatasetsMapper
  ): Future[DeleteResult] = {
    val results: Future[DeleteResult] = for {
      dataset <- db.run(
        datasetTable
          .filter(_.id === job.datasetId)
          .filter(_.state === (DatasetState.DELETING: DatasetState))
          .result
          .head
      )

      datasetDeleteResult <- db
        .run(datasetTable.filter(_.id === dataset.id).delete)
        .map {
          case 1 =>
            DatasetDeleteResult(
              success = true,
              message =
                s"Successfully deleted dataset (${dataset.id}) from Postgres",
              datasetNodeId = Some(dataset.nodeId),
              traceId = job.traceId
            )
          case _ =>
            DatasetDeleteResult(
              success = false,
              message =
                s"Failed to delete dataset (${dataset.id}) from Postgres",
              datasetNodeId = Some(dataset.nodeId),
              traceId = job.traceId
            )
        }
    } yield datasetDeleteResult

    results.recover {
      case e: Exception =>
        DatasetDeleteResult(
          success = false,
          message =
            s"Failed to delete dataset from Postgres with exception: ${e.getMessage}",
          datasetNodeId = None,
          traceId = job.traceId
        )
    }: Future[DeleteResult]
  }

  def deleteDatasetJobWithResult(
    job: DeleteDatasetJob
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, (DeleteResult, DatasetDeletionSummary)] = {

    implicit val context: LogContext =
      CatalogDeleteContext(job.organizationId, job.userId)

    val query =
      OrganizationsMapper.filter(_.id === job.organizationId).result.headOption

    for {
      organization <- db
        .run(query)
        .map(_.toRight(InvalidOrganization(job.organizationId): JobException))
        .toEitherT(ExceptionError)

      datasetTable = new DatasetsMapper(organization)
      assetTable = new DatasetAssetsMapper(organization)

      datasetAssetsManager = new DatasetAssetsManager(
        container.db,
        datasetTable
      )

      assets <- datasetAssetsManager
        .getDatasetAssets(job.datasetId)
        .leftMap(ExceptionError(_))

      _ = log.noContext.info(
        s"""Found ${assets.size} assets to delete: ${assets}"""
      )

      _ <- assets
        .map(datasetAssetClient.deleteAsset(_))
        .sequence
        .toEitherT[Future]
        .leftMap(ExceptionError(_))
      _ = log.noContext.info(s"Permanently deleted assets: ${assets}")

      _ <- assets
        .map(datasetAssetsManager.deleteDatasetAsset(_))
        .sequence
        .leftMap(ExceptionError(_))

      _ <- creditDeleteJob(job)

      // Delete the dataset data in model-service:
      summary <- deleteDatasetFromModelService(job)
        .map(
          summary =>
            Right(summary): Either[JobException, DatasetDeletionSummary]
        )
        .toEitherT(ExceptionError)

      _ = log.noContext.info(
        s"Model service dataset deletion counts: ${summary}"
      )

      // Delete the actual dataset data:
      result <- deleteDataset(job, datasetTable)
        .map(result => Right(result): Either[JobException, DeleteResult])
        .toEitherT(ExceptionError)

      _ <- record(job, result)
        .map(x => Right(x): Either[JobException, Unit])
        .toEitherT(ExceptionError)

    } yield (result, summary)
  }

  def deleteDatasetJob(
    job: DeleteDatasetJob
  )(implicit
    system: ActorSystem,
    ec: ExecutionContext
  ): EitherT[Future, JobException, Unit] =
    deleteDatasetJobWithResult(job).map(_ => ())
}
