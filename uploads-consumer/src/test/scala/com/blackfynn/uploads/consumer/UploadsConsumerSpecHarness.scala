// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.uploads.consumer

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.pennsieve.akka.consumer.ConsumerUtilities
import com.pennsieve.aws.queue.{ LocalSQSContainer, SQSDeduplicationContainer }
import com.pennsieve.aws.s3.{ LocalS3Container, S3 }
import com.pennsieve.clients.{
  MockJobSchedulingServiceContainer,
  MockUploadServiceContainer
}
import com.pennsieve.models.{ NodeCodes, Organization, User }
import com.pennsieve.test.helpers.{ AwaitableImplicits, TestDatabase }
import com.pennsieve.aws.sns.LocalSNSContainer
import com.pennsieve.core.utilities.{ DatabaseContainer, RedisContainer }
import com.pennsieve.db.{ OrganizationsMapper, UserMapper }
import com.pennsieve.test._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.uploads.consumer.antivirus.ClamAVContainer
import com.redis.RedisClientPool
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import com.typesafe.scalalogging.LazyLogging
import org.scalatest._

import scala.concurrent.ExecutionContext

trait UploadsConsumerSpecHarness
    extends SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with TestDatabase
    with LazyLogging
    with PersistantTestContainers
    with PostgresDockerContainer
    with RedisDockerContainer
    with ClamdDockerContainer
    with LocalstackDockerContainer { self: Suite =>

  // Needed for being able to use localstack with SSL enabled,
  // which is required for testing KMS encryption with S3
  System.setProperty("com.amazonaws.sdk.disableCertChecking", "true")

  implicit lazy val system: ActorSystem = ActorSystem(
    "uploads-consumer-spec-harness"
  )
  implicit lazy val executionContext: ExecutionContext = system.dispatcher
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer(
    ConsumerUtilities.actorMaterializerSettings(system, logger)
  )

  val queueName: String = "test-etl-queue"
  val notificationsQueueName: String = "test-notifications-queue"

  val S3Port: Int = 4572

  var consumerContainer: Container = _

  val organization: Organization = Organization(
    nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
    name = "Uploads Consumer Test",
    slug = "uploads-consumer-test",
    encryptionKeyId = Some("test-encryption-key"),
    id = 1
  )

  val user: User = User(
    nodeId = NodeCodes.generateId(NodeCodes.userCode),
    email = "uploads-consumer@test.com",
    firstName = "Uploads Consumer",
    middleInitial = None,
    lastName = "Test User",
    degree = None,
    password = "test-password",
    id = 1
  )

  override def beforeEach(): Unit = {
    super.beforeEach()

    if (consumerContainer == null) {
      throw new RuntimeException(
        s"consumerContainer property of ${this.getClass.getSimpleName} is null. Aborting tests."
      )
    }

    consumerContainer.db.run(clearDB).await

    consumerContainer.db.run(UserMapper += user).await
    consumerContainer.db.run(OrganizationsMapper += organization).await
    consumerContainer.db.run(createSchema(organization.schemaId)).await
    migrateOrganizationSchema(
      organization.id,
      consumerContainer.postgresDatabase
    )
  }

  override def afterEach: Unit = {
    consumerContainer.db.run(clearOrganizationSchema(organization.id)).await
    super.afterEach()
  }

  override def afterStart(): Unit = {
    super.afterStart()
    consumerContainer = new ConsumerContainer(config) with DatabaseContainer
    with RedisContainer with LocalSQSContainer with LocalS3Container
    with SQSDeduplicationContainer with ClamAVContainer with LocalSNSContainer
    with MockUploadServiceContainer with MockJobSchedulingServiceContainer {
      import net.ceedubs.ficus.Ficus._
      override lazy val jobSchedulingServiceConfigPath: String =
        "job_scheduling_service"
      override lazy val materializer: ActorMaterializer =
        ActorMaterializer()
      override lazy val jobSchedulingServiceHost: String =
        config.as[String](s"$jobSchedulingServiceConfigPath.host")
      override lazy val jobSchedulingServiceQueueSize: Int =
        config.as[Int](s"$jobSchedulingServiceConfigPath.queue_size")
      override lazy val jobSchedulingServiceRateLimit: Int =
        config.as[Int](s"$jobSchedulingServiceConfigPath.rate_limit")

      override lazy val uploadServiceHost: String =
        config.as[String](s"$uploadServiceConfigPath.host")

      override val postgresUseSSL = false
    }
  }

  def config: Config = {
    ConfigFactory
      .empty()
      .withFallback(clamdContainer.config)
      .withFallback(postgresContainer.config)
      .withFallback(redisContainer.config)
      .withFallback(localstackContainer.config)
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
      .withValue(
        "notifications.queue",
        ConfigValueFactory.fromAnyRef(s"queue/$notificationsQueueName")
      )
      .withValue("parallelism", ConfigValueFactory.fromAnyRef(1))
      .withValue(
        "s3.buckets.etl",
        ConfigValueFactory.fromAnyRef("test-uploads-pennsieve")
      )
      .withValue(
        "s3.buckets.storage",
        ConfigValueFactory.fromAnyRef("test-storage-pennsieve")
      )
      .withValue(
        "s3.buckets.uploads",
        ConfigValueFactory.fromAnyRef("test-uploads-pennsieve")
      )
      .withValue(
        "sqs.queue",
        ConfigValueFactory.fromAnyRef(s"queue/$queueName")
      )
      .withValue(
        "sqs.region",
        ConfigValueFactory.fromAnyRef(localstackContainer.region)
      )
      .withValue("sqs.deduplication.ttl", ConfigValueFactory.fromAnyRef(2))
      .withValue(
        "sqs.deduplication.redisDBIndex",
        ConfigValueFactory.fromAnyRef(4)
      )
      .withValue("jwt.key", ConfigValueFactory.fromAnyRef("testkey"))
      .withValue(
        "job_scheduling_service.host",
        ConfigValueFactory.fromAnyRef("test-job-scheduling-service-url")
      )
      .withValue(
        "job_scheduling_service.queue_size",
        ConfigValueFactory.fromAnyRef(100)
      )
      .withValue(
        "job_scheduling_service.rate_limit",
        ConfigValueFactory.fromAnyRef(10)
      )
  }

  override def afterAll: Unit = {
    consumerContainer.db.close()
    consumerContainer.snsClient.close()
    consumerContainer.sqs.client.close()
    consumerContainer.s3.asInstanceOf[S3].client.shutdown()
    super.afterAll()
  }
}
