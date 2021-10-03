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

package com.pennsieve.uploads.consumer

import java.io._
import java.util.UUID
import java.util.concurrent.locks._

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{
  CopyObjectRequest,
  ObjectMetadata,
  PutObjectResult
}
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.aws.s3.{ LocalS3Container, _ }
import com.pennsieve.aws.sns.LocalSNSContainer
import com.pennsieve.clients.{
  MockJobSchedulingServiceClient,
  MockJobSchedulingServiceContainer,
  MockUploadServiceContainer
}
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.{
  getFileType,
  splitFileName,
  DatabaseContainer
}
import com.pennsieve.db.OrganizationsMapper
import com.pennsieve.managers.FileManager.UploadSourceFile
import com.pennsieve.managers.{ DatasetManager, FileManager, PackageManager }
import com.pennsieve.models
import com.pennsieve.models.Utilities.escapeName
import com.pennsieve.models.{
  Dataset,
  FileChecksum,
  FileHash,
  FileProcessingState,
  FileState,
  FileType,
  JobId,
  Manifest,
  NodeCodes,
  Package,
  PackageState,
  PackageType,
  PayloadType,
  Upload
}
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.uploads.consumer.antivirus._
import org.apache.commons.io.FilenameUtils
import org.testcontainers.shaded.org.apache.commons.io.FileUtils
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration._

class UploadHandlerSpec extends UploadsConsumerDatabaseSpecHarness {

  var dataset: Dataset = _
  val log: ContextLogger = new ContextLogger()

  var dm: DatasetManager = _
  var pm: PackageManager = _
  var fm: FileManager = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    consumerContainer.s3.createBucket(consumerContainer.uploadsBucket).value
    consumerContainer.s3.createBucket(consumerContainer.storageBucket).value

    dataset = createDataset

    dm = new DatasetManager(consumerContainer.db, user, datasets)
    pm = new PackageManager(dm)
    fm = new FileManager(pm, organization)
  }

  "UploadHandler.handle" should {

    "process a clean file with a workflow" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value should equal(Clean)

      // test file was copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey)
        .value

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was created
      val sourceFiles = getFiles(`package`)
      sourceFiles.map(_.s3Bucket) should contain theSameElementsAs Seq(
        consumerContainer.storageBucket
      )
      sourceFiles.map(_.s3Key) should contain theSameElementsAs Seq(storageKey)

      // test job was marked as available in Job Scheduling Service
      consumerContainer.jobsClient
        .asInstanceOf[MockJobSchedulingServiceClient]
        .notProcessingJobs should contain(jobId)
    }

    "process a clean file with a workflow and add pending state if the dataset is locked for publication" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      sendPublishRequest(dataset, user)

      runHandler(jobId, payload).value should equal(Clean)

      // test file was copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey)
        .value

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was created
      val sourceFiles = getFiles(`package`)

      sourceFiles.map(_.uploadedState) shouldBe Seq(Some(FileState.PENDING))
      sourceFiles.map(_.s3Bucket) should contain theSameElementsAs Seq(
        consumerContainer.storageBucket
      )
      sourceFiles.map(_.s3Key) should contain theSameElementsAs Seq(storageKey)

      // test job was marked as available in Job Scheduling Service
      consumerContainer.jobsClient
        .asInstanceOf[MockJobSchedulingServiceClient]
        .notProcessingJobs should contain(jobId)
    }

    "process a clean file with a workflow and not add pending state if the dataset is locked for publication but" +
      " the upload was from a member of the publishing team" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      sendPublishRequest(dataset, user)

      createPublisherTeamAndAttachToDataset(user, dataset)

      runHandler(jobId, payload).value should equal(Clean)

      // test source was created
      val sourceFiles = getFiles(`package`)

      sourceFiles.map(_.uploadedState) shouldBe Seq(Some(FileState.UPLOADED))
    }

    "process a clean file with spaces in the name" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello spaces.txt")
      val storageKey: String = createStorageKey(jobId, "hello_spaces.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(
            getClass
              .getResource("/inputs/hello spaces.txt")
              .getPath
              .replace("%20", " ")
          )
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value should equal(Clean)

      // test file was copied to storage bucket
      consumerContainer.s3.getObject(
        consumerContainer.storageBucket,
        storageKey
      ) should be('right)

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was created
      getFiles(`package`).map(_.s3Key) should contain theSameElementsAs Seq(
        storageKey
      )
      getFiles(`package`).map(_.name) should equal(Vector("hello spaces"))
    }

    "process a clean file without a workflow" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value shouldEqual Clean

      // test file was copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey)
        .value

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was created
      getFiles(`package`).map(_.s3Key) should contain theSameElementsAs Seq(
        storageKey
      )

      // test manifest was not uploaded to etl bucket
      consumerContainer.s3.getObject(
        consumerContainer.etlBucket,
        UploadHandler.manifestKey(jobId)
      ) should be('left)

      // test package is still UNAVAILABLE
      getPackage(`package`.id).state should be(PackageState.UNAVAILABLE)

      // test job was marked NOT_PROCESSING in the job scheduling service
      consumerContainer.jobsClient
        .asInstanceOf[MockJobSchedulingServiceClient]
        .notProcessingJobs should contain(jobId)
    }

    "process a clean file and ignore a concurrent call" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      val manifest: Manifest =
        models.Manifest(PayloadType.Upload, jobId, organization.id, payload)

      val copyLock = new ReentrantLock()
      copyLock.lock()

      val futureResult = UploadHandler
        .handle(manifest)(
          synchronizedConsumerContainer(copyLock),
          executionContext,
          system,
          log
        )

      // Wait for handler to hit lock
      while (!copyLock.hasQueuedThreads) {
        Thread.sleep(1000)
      }

      try {
        UploadHandler
          .handle(manifest)(consumerContainer, executionContext, system, log)
          .value
          .await shouldBe Right(Locked)
      } finally {
        copyLock.unlock()
      }

      futureResult.value.await shouldBe Right(Clean)

      // test file was copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey)
        .value

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // only one set of sources was created
      val sourceFiles = getFiles(`package`)
      sourceFiles.map(_.s3Key) should contain theSameElementsAs Seq(storageKey)
      sourceFiles.map(_.processingState) should contain theSameElementsAs Seq(
        FileProcessingState.Unprocessed
      )

      // Package should still be unavailable
      getPackage(`package`.id).state should be(PackageState.UNAVAILABLE)
    }

    "shortcircuit processing when package is locked during concurrent call" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val upload1Key: String = createUploadKey(jobId, "hello1.txt")
      val upload2Key: String = createUploadKey(jobId, "hello2.txt")

      val storage1Key: String = createStorageKey(jobId, "hello1.txt")
      val storage2Key: String = createStorageKey(jobId, "hello2.txt")

      val upload1: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          upload1Key,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val upload2: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          upload2Key,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(
          `package`.id,
          List((upload1Key, upload1), (upload2Key, upload2)),
          FileState.SCANNING,
          true
        )

      val manifest: Manifest =
        models.Manifest(PayloadType.Upload, jobId, organization.id, payload)

      // Slow container - will scan for 5 seconds

      // UC1: starts scan - locked
      // UC2: find locked package, exit immediately and bump visibility timeout
      // UC1: complete processing

      // Block UC1 while scanning
      val copyLock = new ReentrantLock()
      copyLock.lock()

      val futureResult = UploadHandler
        .handle(manifest)(
          synchronizedConsumerContainer(copyLock),
          executionContext,
          system,
          log
        )

      // Wait for handler to hit lock
      while (!copyLock.hasQueuedThreads) {
        Thread.sleep(1000)
      }

      try {
        UploadHandler
          .handle(manifest)(consumerContainer, executionContext, system, log)
          .value
          .await shouldBe Right(Locked)

      } finally {
        copyLock.unlock()
      }

      futureResult.value.await shouldBe Right(Clean)

      // test files were copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storage1Key)
        .value
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storage2Key)
        .value

      // test uploaded files were deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, upload1Key) should be('left)
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, upload2Key) should be('left)

      // only one pair of sources was created
      getFiles(`package`).map(_.s3Key) should contain theSameElementsAs Seq(
        storage1Key,
        storage2Key
      )

      // Package should still be unavailable
      getPackage(`package`.id).state should be(PackageState.UNAVAILABLE)
    }

    "not block package updates when package is locked" in {
      // This test recreates the scenario when:
      //
      // 1. uploads-consumer acquires package lock
      // 2. uploads-consumer scans file
      // 3. concurrently:
      //   a. uploads-consumer moves source file and creates source
      //   b. a user updates the package via API
      //
      // If moving the file takes a long time, and the file is added to the
      // database *before* the move starts, then other queries can deadlock.

      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")

      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      val manifest: Manifest =
        models.Manifest(PayloadType.Upload, jobId, organization.id, payload)

      // Block the upload handler while it is moving files to S3
      val copyLock = new ReentrantLock()
      copyLock.lock()

      val futureResult = UploadHandler
        .handle(manifest)(
          synchronizedConsumerContainer(copyLock = copyLock),
          executionContext,
          system,
          log
        )

      // Wait for handler to hit lock
      while (!copyLock.hasQueuedThreads) {
        Thread.sleep(1000)
      }

      try {

        // This query can deadlock if it is waiting on the `createSource` db query
        val updateQuery = for {
          _ <- packages.get(`package`.id).result
          updated <- packages
            .get(`package`.id)
            .update(`package`.copy(name = "New Name"))
        } yield updated

        consumerContainer.db
          .run(updateQuery)
          .awaitFinite(1.second) shouldBe 1

      } finally {
        copyLock.unlock()
      }

      futureResult.value.await shouldBe Right(Clean)
    }

    /**
      * Synchronized container that can be paused while scanning/moving files.
      */
    def synchronizedConsumerContainer(copyLock: ReentrantLock) = {

      class SynchronizedS3(override val client: AmazonS3) extends S3(client) {
        // This function is used by `moveAsset`
        override def copyObject(request: CopyObjectRequest) = {
          copyLock.lock()
          try {
            super.copyObject(request)
          } finally {
            copyLock.unlock()
          }
        }
      }

      new ConsumerContainer(config) with DatabaseContainer
      with LocalSQSContainer with LocalS3Container with ClamAVContainer
      with LocalSNSContainer with MockUploadServiceContainer
      with MockJobSchedulingServiceContainer {
        override lazy val jobSchedulingServiceConfigPath: String =
          "job_scheduling_service"
        override lazy val jobSchedulingServiceHost: String =
          config.as[String](s"$jobSchedulingServiceConfigPath.host")
        override lazy val jobSchedulingServiceQueueSize: Int =
          config.as[Int](s"$jobSchedulingServiceConfigPath.queue_size")
        override lazy val jobSchedulingServiceRateLimit: Int =
          config.as[Int](s"$jobSchedulingServiceConfigPath.rate_limit")

        override lazy val uploadServiceHost: String =
          config.as[String](s"$uploadServiceConfigPath.host")

        override val postgresUseSSL = false

        override lazy val s3: S3 =
          new SynchronizedS3(consumerContainer.s3.asInstanceOf[S3].client)
      }
    }

    "process a clean file with special characters in the filename and pass the correct filename to the upload service" in {
      val fileName = "hello!! weird=filename.txt"
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, fileName)

      val metadata = new ObjectMetadata()
      metadata.addUserMetadata("chunksize", "500000")

      val bytes = new ByteArrayInputStream(
        FileUtils.readFileToByteArray(
          new File(
            getClass
              .getResource(s"/inputs/$fileName")
              .getPath
              .replace("%20", " ")
              .replace("%3d", "=")
          )
        )
      )

      val upload = consumerContainer.s3
        .putObject(consumerContainer.uploadsBucket, uploadKey, bytes, metadata)
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value should equal(Clean)

      getFiles(`package`).map(_.name) should equal(
        Vector("hello!! weird%3Dfilename")
      )
    }

    "process a clean file and move it to the SPARC organization storage bucket" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val sparcStorageBucket = "sparc-storage-use1"
      consumerContainer.s3.createBucket(sparcStorageBucket).value

      consumerContainer.db
        .run(
          OrganizationsMapper
            .filter(_.id === organization.id)
            .map(_.storageBucket)
            .update(Some(sparcStorageBucket))
        )
        .await

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value should equal(Clean)

      // file was copied to SPARC storage bucket
      val storedObject = consumerContainer.s3
        .getObject(sparcStorageBucket, storageKey)
        .value

      // file was not copied to regular storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey) should be('left)

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was created
      getFiles(`package`).map(f => (f.s3Bucket, f.s3Key)) should contain theSameElementsAs Seq(
        (sparcStorageBucket, storageKey)
      )
    }

    "process an infected file" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "eicar-av-test-file")
      val storageKey: String = createStorageKey(jobId, "eicar-av-test-file")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/eicar-av-test-file").getPath)
        )
        .value

      val payload: Upload =
        createPayload(`package`.id, uploadKey, upload)

      val result = runHandler(jobId, payload).value

      result shouldEqual Infected

      // test file was not copied to storage bucket
      consumerContainer.s3
        .getObject(consumerContainer.storageBucket, storageKey) should be('left)

      // test uploaded file was deleted
      consumerContainer.s3
        .getObject(consumerContainer.uploadsBucket, uploadKey) should be('left)

      // test source was not created
      getFiles(`package`).map(_.s3Key) should be(empty)

      getPackage(`package`.id).state should be(PackageState.INFECTED)

      // test manifest was not uploaded to etl bucket
      consumerContainer.s3.getObject(
        consumerContainer.etlBucket,
        UploadHandler.manifestKey(jobId)
      ) should be('left)
    }

    "ignore a file that has already been moved" in {
      val jobId = createJobId
      val `package`: Package = createPackage(dataset, `type` = PackageType.Text)

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload =
        consumerContainer.s3
          .putObject(
            consumerContainer.storageBucket,
            storageKey,
            new File(getClass.getResource("/inputs/hello.txt").getPath)
          )
          .value

      val payload =
        createPayload(`package`.id, uploadKey, upload)

      runHandler(jobId, payload).value shouldEqual Clean

      getPackage(`package`.id).state should be(PackageState.UNAVAILABLE)
    }

    "gracefully handle when a package does not exist" in {
      val jobId = createJobId

      val nonExistentPackage: Package = Package(
        nodeId = NodeCodes.generateId(NodeCodes.packageCode),
        name = "Test Package",
        `type` = PackageType.Text,
        datasetId = dataset.id,
        ownerId = Some(user.id),
        state = PackageState.UNAVAILABLE,
        importId = Some(UUID.randomUUID),
        id = 1
      )

      val uploadKey: String = createUploadKey(jobId, "hello.txt")
      val storageKey: String = createStorageKey(jobId, "hello.txt")

      val upload: PutObjectResult = consumerContainer.s3
        .putObject(
          consumerContainer.uploadsBucket,
          uploadKey,
          new File(getClass.getResource("/inputs/hello.txt").getPath)
        )
        .value

      val payload: Upload =
        createPayloadWithoutFiles(
          nonExistentPackage.id,
          "does-not-exist" + uploadKey,
          upload
        )

      runHandler(jobId, payload) should be('left)
    }
  }

  private def runHandler(
    jobId: JobId,
    payload: Upload
  ): Either[Throwable, ScanResult] = {
    val manifest: Manifest =
      models.Manifest(PayloadType.Upload, jobId, organization.id, payload)

    UploadHandler
      .handle(manifest)(consumerContainer, executionContext, system, log)
      .value
      .await
  }

  private def createJobId = new JobId(UUID.randomUUID)

  private def createStorageKey(jobId: JobId, filename: String): String =
    s"test@pennsieve.org/$jobId/$filename"

  private def createUploadKey(jobId: JobId, filename: String): String =
    s"test@pennsieve.org/$jobId/$filename"

  private def createPayload(
    packageId: Int,
    uploadKeysAndUploads: List[(String, PutObjectResult)],
    fileState: FileState = FileState.SCANNING,
    withFiles: Boolean = true
  ): Upload = {

    val srcFiles = uploadKeysAndUploads.map {
      case (s3Key, _) => {
        val fileName =
          FilenameUtils.removeExtension(FilenameUtils.getName(s3Key))

        val (_, extension) = splitFileName(fileName)
        val fileType = getFileType(extension)
        UploadSourceFile(
          fileName,
          fileType,
          s3Key,
          500000,
          Some(FileChecksum(500000, "hash"))
        )
      }
    }

    if (withFiles) {
      fm.generateSourcesFiles(
          packageId,
          consumerContainer.uploadsBucket,
          srcFiles,
          fileState
        )
        .await
    }

    Upload(
      packageId = packageId,
      datasetId = dataset.id,
      userId = user.id,
      encryptionKey = utilities.encryptionKey(organization).value,
      files = uploadKeysAndUploads.map(
        u => s"s3://${consumerContainer.uploadsBucket}/${u._1}"
      ),
      size = uploadKeysAndUploads
        .map(_._2.getMetadata.getContentLength)
        .fold(0L)(_ + _)
    )
  }

  private def createPayload(
    packageId: Int,
    uploadKey: String,
    upload: PutObjectResult
  ): Upload =
    createPayload(
      packageId,
      List((uploadKey, upload)),
      fileState = FileState.SCANNING,
      withFiles = true
    )

  private def createPayloadWithoutFiles(
    packageId: Int,
    uploadKey: String,
    upload: PutObjectResult,
    fileState: FileState = FileState.SCANNING
  ): Upload =
    createPayload(
      packageId,
      List((uploadKey, upload)),
      fileState,
      withFiles = false
    )
}
