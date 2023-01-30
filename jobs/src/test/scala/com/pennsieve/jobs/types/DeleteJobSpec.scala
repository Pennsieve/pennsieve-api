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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.testkit.TestKitBase
import cats.implicits._
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.pennsieve.audit.middleware.{ Auditor, ToMessage, TraceId }
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.clients._
import com.pennsieve.db.{ DatasetsMapper, PackagesMapper }
import com.pennsieve.domain.{ CoreError, ThrowableError }
import com.pennsieve.jobs._
import com.pennsieve.jobs.types.DeleteJob.Container
import com.pennsieve.managers.{ DatasetAssetsManager, ManagerSpec }
import com.pennsieve.messages._
import com.pennsieve.models.FileType.Aperio
import com.pennsieve.models.PackageType.{ ExternalFile, Slide, TimeSeries }
import com.pennsieve.models.{ DatasetState, NodeCodes, PackageState, User }
import com.pennsieve.streaming.{ LookupResultRow, RangeLookUp }
import com.pennsieve.test._
import com.github.tminglei.slickpg.Range
import com.pennsieve.test.helpers.EitherBePropertyMatchers
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.commons.io.IOUtils
import org.scalatest.EitherValues._
import org.scalatest._
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalikejdbc.ConnectionPool
import scalikejdbc.scalatest.AutoRollback

import scala.jdk.CollectionConverters._
import scala.collection.SortedSet
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ ExecutionContextExecutor, Future }

class MockAuditLogger extends Auditor {
  override def enhance[T](
    traceId: TraceId,
    payload: T
  )(implicit
    converter: ToMessage[T]
  ): Future[Unit] = {
    Future.successful(())
  }
}

class MockModelServiceClient extends ModelServiceV2Client {

  var responses: ListBuffer[Either[JobException, DatasetDeletionSummary]] =
    ListBuffer.empty

  def queueSuccessResponse(
    done: Boolean = false,
    models: Int = 0,
    properties: Int = 0,
    records: Int = 0,
    packages: Int = 0,
    relationshipStubs: Int = 0
  ) =
    responses += Right(
      DatasetDeletionSummary(
        done = done,
        DatasetDeletionCounts(
          models = models,
          properties = properties,
          records = records,
          packages = packages,
          relationshipStubs = relationshipStubs
        )
      )
    )

  def queueFailureResponse(error: JobException) = responses += Left(error)

  def clearResponses() = responses.clear()

  override def deleteDataset[B: ToBearer](
    token: B,
    organizationId: Int,
    datasetId: Int
  ): Either[CoreError, DatasetDeletionSummary] = {
    responses.toList match {
      case r :: _ => {
        responses.remove(0)
        r.leftMap(e => ThrowableError(e))
      }
      case Nil => Right(DatasetDeletionSummary.done)
    }
  }
}

class DeleteJobSpec
    extends FixtureAnyFlatSpec
    with SpecHelper
    with Matchers
    with TestKitBase
    with PersistantTestContainers
    with BeforeAndAfterAll
    with ManagerSpec
    with S3DockerContainer
    with AutoRollback
    with EitherBePropertyMatchers {

  def config: Config = {
    ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(s3Container.config)
  }

  implicit lazy val system: ActorSystem = ActorSystem("DeleteJobSpec")
  implicit lazy val executionContext: ExecutionContextExecutor =
    system.dispatcher

  implicit lazy val jwt: Jwt.Config = new Jwt.Config {
    override def key: String = "testkey"
  }

  var processor
    : Flow[CatalogDeleteJob, (CatalogDeleteJob, DeleteResult), NotUsed] = _
  var s3: AmazonS3 = _
  var rangeLookup: RangeLookUp = _
  var deleteJob: DeleteJob = _
  var packageTable: PackagesMapper = _
  var datasetTable: DatasetsMapper = _
  var mockDatasetAssetClient: MockDatasetAssetClient = _
  var mockModelServiceClient: MockModelServiceClient = _
  var mockAuditLogger: Auditor = _
  var diContainer: Container = _

  val dataBucketName: String = "data"
  val timeSeriesBucketName: String = "timeseries"
  val objectKey: String = "delete_me"

  val traceId = TraceId("1234-5678")

  val loggedInUser = new User(
    nodeId = NodeCodes.generateId(NodeCodes.userCode),
    email = "jim@pennsieve.org",
    firstName = "jim",
    middleInitial = None,
    lastName = "snavely",
    degree = None,
    credential = "cred",
    color = "red",
    url = ""
  )

  override def afterStart(): Unit = {
    super.afterStart()

    // Tabular database connection
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton(
      postgresContainer.jdbcUrl(),
      postgresContainer.user,
      postgresContainer.password
    )

    s3 = s3Container.s3Client
    s3.listBuckets().asScala.foreach(bucket => deleteBucket(bucket.getName()))

    s3.createBucket(dataBucketName)
    s3.createBucket(timeSeriesBucketName)

    rangeLookup = new RangeLookUp("", "")

    diContainer = new LocalContainer(config) {
      override val postgresUseSSL = false
      override val dataPostgresUseSSL = false
    }

    mockAuditLogger = new MockAuditLogger()

    mockDatasetAssetClient = new MockDatasetAssetClient()

    mockModelServiceClient = new MockModelServiceClient()

    deleteJob = new DeleteJob(
      db = database,
      amazonS3 = s3,
      rangeLookup = rangeLookup,
      timeSeriesBucketName = timeSeriesBucketName,
      auditLogger = mockAuditLogger,
      datasetAssetClient = mockDatasetAssetClient,
      modelServiceClient = mockModelServiceClient,
      container = diContainer
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    diContainer.dataDB.run(clearDBSchema).await

    packageTable = new PackagesMapper(testOrganization)
    datasetTable = new DatasetsMapper(testOrganization)
    processor = deleteJob.deleteJobFlow(packageTable, testOrganization)
  }

  override def afterAll(): Unit = {
    diContainer.dataDB.close()
    diContainer.db.close()
    mockModelServiceClient.clearResponses()
    shutdown(system)
    super.afterAll()
  }

  /**
    * Delete all objects from bucket, and delete the bucket itself
    */
  def deleteBucket(bucket: String): Unit = {
    listBucket(bucket)
      .map(o => s3.deleteObject(bucket, o.getKey()))
    s3.deleteBucket(bucket)
  }

  def listBucket(bucket: String): Seq[S3ObjectSummary] =
    s3.listObjectsV2(bucket)
      .getObjectSummaries()
      .asScala
      .toSeq

  behavior of "DeleteJob"

  it should "handle regular files" in { _ =>
    // upload file to s3
    s3.putObject(dataBucketName, objectKey, "Delete Me")

    // create package
    val user = createUser(email = "deleter@test.com")
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val slidePackage = createPackage(
      user = user,
      state = PackageState.DELETING,
      parent = Some(parent),
      dataset = dataset,
      `type` = Slide
    )

    // add file node under package with s3 path
    val file = createFile(
      container = slidePackage,
      user = user,
      s3Bucket = dataBucketName,
      s3Key = objectKey
    )

    // send delete msg
    val msg: CatalogDeleteJob =
      DeletePackageJob(
        packageId = slidePackage.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    assert(deleteJob.creditDeleteJob(msg).await.isRight)

    // make sure item is removed from the database
    // val pm = packageManager(user = user)
    // pm.get(slidePackage.id).await should be a (left)

    // val fm = fileManager(user = user)
    // fm.get(file.id, slidePackage).await should be a (left)

    // make sure item is not in s3
    // s3.listObjects(dataBucketName).getObjectSummaries.asScala.size should be(0)
  }

  it should "handle external files" in { _ =>
    // create package
    val user = createUser(email = "deleter@test.com")
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val externalPackage = createPackage(
      user = user,
      state = PackageState.DELETING,
      parent = Some(parent),
      dataset = dataset,
      `type` = ExternalFile,
      externalLocation = Some("file:///home/cloud/important_stuff/big-guy.vm"),
      description = Some("very very important stuff")
    )

    // send delete msg
    val msg: CatalogDeleteJob =
      DeletePackageJob(
        packageId = externalPackage.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    assert(deleteJob.creditDeleteJob(msg).await.isRight)

    // make sure item is removed from the database
    // TODO: Check that package state is DELETED
    // val pm = packageManager(user = user)
    // pm.get(externalPackage.id).await should be a (left)

    // The associated external file should be gone too:
    // val result = externalFileManager(testOrganization, loggedInUser)
    //   .get(externalPackage)
    //   .await

    // // Non-existent external file is an error
    // assert(result.isLeft)
  }

  // TODO: update this test to delete from `model-service`

  ignore should "handle packages connected to the graph" in { _ =>
    // create package
    val user = createUser(email = "deleter@test.com")
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val slidePackage = createPackage(
      user = user,
      state = PackageState.DELETING,
      parent = Some(parent),
      dataset = dataset,
      `type` = Slide
    )

    // send delete msg
    val msg: CatalogDeleteJob =
      DeletePackageJob(
        packageId = slidePackage.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    fail("Deleting from `model-service` is not implemented")

    assert(deleteJob.creditDeleteJob(msg).await.isRight)

    val pm = packageManager(user = user)
    pm.get(slidePackage.id).await should be a (left)
  }

  // test aperio file type
  it should "handle directories" in { _ =>
    // upload file to s3
    val objectDir = "dir"
    val object1Key = s"$objectDir/delete_me1"
    val object2Key = s"$objectDir/delete_me2"
    s3.putObject(dataBucketName, object1Key, "Delete Me 1")
    s3.putObject(dataBucketName, object2Key, "Delete Me 2")

    // create package
    val user = createUser(email = "deleter@test.com")
    val dataset = createDataset(user = user)
    val parent = createPackage(
      user = user,
      dataset = dataset,
      state = PackageState.DELETING
    )
    val slidePackage = createPackage(
      user = user,
      `type` = Slide,
      parent = Some(parent),
      dataset = dataset
    )

    // add file node under package with s3 path
    val file = createFile(
      container = slidePackage,
      user = user,
      s3Bucket = dataBucketName,
      s3Key = objectDir,
      fileType = Aperio
    )

    // send delete msg
    val msg: CatalogDeleteJob =
      DeletePackageJob(
        packageId = parent.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    assert(deleteJob.creditDeleteJob(msg).await.isRight)

    // make sure item is removed from the database
    // val pm = packageManager(user = user)
    // pm.get(parent.id).await should be a (left)
    // pm.get(slidePackage.id).await should be a (left)

    // val fm = fileManager(organization = testOrganization, user = user)
    // fm.get(file.id, slidePackage).await should be a (left)

    // make sure item is not in s3
    s3.listObjects(dataBucketName).getObjectSummaries.asScala.size should be(0)
  }

  it should "handle timeseries channels and data" in { implicit session =>
    deleteJob.autoSession = session
    rangeLookup.autoSession = session

    val user = createUser(email = "deleter@test.com")
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val timeseriesPackage = createPackage(
      user = user,
      state = PackageState.DELETING,
      `type` = TimeSeries,
      parent = Some(parent),
      dataset = dataset
    )

    // file
    s3.putObject(dataBucketName, objectKey, "Delete Me")
    val file = createFile(
      container = timeseriesPackage,
      user = user,
      s3Bucket = dataBucketName,
      s3Key = objectKey
    )

    val tm = timeSeriesManager()
    // channel
    val channel = tm
      .createChannel(timeseriesPackage, "test", 0, 100, "", 1, "type", None, 0)
      .await
      .value

    val lookups = (0 to 9).map { i =>
      val s3Key = s"delete_me$i"
      s3.putObject(timeSeriesBucketName, s3Key, s"Delete Me $i")
      val range = LookupResultRow(
        id = 0,
        min = i * 10,
        max = i * 10 + 10,
        sampleRate = 2.0,
        channel = channel.nodeId,
        file = s3Key
      )
      val newId = rangeLookup.addRangeLookup(range)
      range.copy(id = newId)
    }

    // layer, annotation and channel group
    val layer = diContainer.layerManager
      .create(timeseriesPackage.nodeId, "test", Some("desc"))
      .await
    val annotation = diContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "label",
        description = Some("desc"),
        userNodeId = user.nodeId,
        range = Range[Long](0, 10),
        channelIds = SortedSet(channel.nodeId),
        data = None
      )(tm)
      .await
      .value

    val msg: CatalogDeleteJob =
      DeletePackageJob(
        packageId = timeseriesPackage.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    assert(deleteJob.creditDeleteJob(msg).await.isRight)

    // expect data in all systems to be gone
    // val pm = packageManager(user = user)
    // pm.get(timeseriesPackage.id).await should be a (left)
    // tm.getChannel(channel.id, timeseriesPackage).await should be a (left)

    // val fm = fileManager(organization = testOrganization, user = user)
    // fm.get(file.id, timeseriesPackage).await should be a (left)

    rangeLookup.get(channel.nodeId).size should be(0)
    diContainer.layerManager.getBy(layer.id).await should not be defined
    diContainer.timeSeriesAnnotationManager
      .getBy(annotation.id)
      .await should not be defined
    diContainer.channelGroupManager
      .getBy(SortedSet(channel.nodeId))
      .await should not be defined
    s3.listObjects(dataBucketName).getObjectSummaries.asScala.size should be(0)
    s3.listObjects(timeSeriesBucketName)
      .getObjectSummaries
      .asScala
      .size should be(0)
  }

  it should "handle datasets" in { _ =>
    // upload file to s3
    s3.putObject(dataBucketName, objectKey, "Delete Me")

    // create package
    val user = createUser(email = "deleter@test.com")

    val dm = datasetManager(user = user)
    val pm = packageManager(user = user)

    var dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    var slidePackage = createPackage(
      user = user,
      parent = Some(parent),
      dataset = dataset,
      `type` = Slide
    )

    // Model service client responses:
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueSuccessResponse(
      done = true,
      records = 3000,
      models = 10,
      properties = 50
    )

    //create asset

    val content = "#Markdown content\nA paragraph!"

    lazy val datasetAssetsManager: DatasetAssetsManager =
      new DatasetAssetsManager(database, datasetTable)

    val readmeAsset = datasetAssetsManager
      .createOrUpdateReadme(
        dataset,
        mockDatasetAssetClient.bucket,
        "readme.md",
        asset =>
          mockDatasetAssetClient.uploadAsset(
            asset,
            content.getBytes("utf-8").length,
            Some("text/plain"),
            IOUtils.toInputStream(content, "utf-8")
          )
      )
      .await

    val file = createFile(
      container = slidePackage,
      user = user,
      s3Bucket = dataBucketName,
      s3Key = objectKey
    )

    // Update the dataset state to `DELETING`:
    dataset = dataset.copy(state = DatasetState.DELETING)
    dm.update(dataset)

    // Update the package state to `DELETING`:
    slidePackage = slidePackage.copy(state = PackageState.DELETING)
    pm.update(slidePackage)

    // send delete dataset job
    val job: DeleteDatasetJob =
      DeleteDatasetJob(
        datasetId = dataset.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    val deleteJobResult = deleteJob.deleteDatasetJobWithResult(job).await
    val (deleteResult, deleteSummary) = deleteJobResult.value
    assert(deleteSummary.done)
    assert(deleteSummary.counts.models == 10)
    assert(deleteSummary.counts.properties == 50)
    assert(deleteSummary.counts.records == 18000)

    // make sure item is removed from the database

    pm.get(slidePackage.id).await should be a (left)
    pm.get(parent.id).await should be a (left)

    dm.get(dataset.id).await should be a (left)

    val fm = fileManager(organization = testOrganization, user = user)
    fm.get(file.id, slidePackage).await should be a (left)

    assert(
      datasetAssetsManager.getDatasetAssets(dataset.id).await == Right(List())
    )

    mockDatasetAssetClient.assets shouldBe empty

    // make sure item is not in s3
    s3.listObjects(dataBucketName).getObjectSummaries.asScala.size should be(0)

  }

  it should "handle model-service dataset deletion failure" in { _ =>
    // create package
    val user = createUser(email = "deleter@test.com")

    val dm = datasetManager(user = user)
    val pm = packageManager(user = user)

    var dataset = createDataset(user = user)

    // Model service client responses:
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueFailureResponse(
      ExceptionError(new Throwable("service not reachable"))
    )
    mockModelServiceClient.queueSuccessResponse(records = 5000)
    mockModelServiceClient.queueSuccessResponse(
      done = true,
      records = 3000,
      models = 10,
      properties = 50
    )

    // send delete dataset job
    val job: DeleteDatasetJob =
      DeleteDatasetJob(
        datasetId = dataset.id,
        organizationId = testOrganization.id,
        userId = user.nodeId,
        traceId = traceId
      )

    val deleteJobResult = deleteJob.deleteDatasetJobWithResult(job).await
    assert(deleteJobResult.isLeft)
    val err = deleteJobResult.left.value
    err.getMessage should be("service not reachable")
  }
}
