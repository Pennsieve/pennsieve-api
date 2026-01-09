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

package com.pennsieve.helpers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import com.amazonaws.ClientConfiguration
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.S3ClientOptions
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.cognito.{ CognitoClient, CognitoConfig }
import com.pennsieve.aws.ecs.{ AWSECS, ECSTrait }
import com.pennsieve.aws.s3.AWSS3Container
import com.pennsieve.aws.s3.LocalS3Container
import com.amazonaws.services.ecs.AmazonECSAsyncClientBuilder
import net.ceedubs.ficus.Ficus._
import com.pennsieve.aws.email.{
  AWSEmailContainer,
  EmailContainer,
  LocalEmailContainer
}
import com.pennsieve.clients._
import com.pennsieve.aws.cognito.Cognito
import com.pennsieve.aws.sns.{
  AWSSNSContainer,
  LocalSNSContainer,
  SNS,
  SNSClient,
  SNSContainer
}
import com.pennsieve.aws.queue.{
  AWSSQSContainer,
  LocalSQSContainer,
  SQS,
  SQSClient,
  SQSContainer
}
import com.pennsieve.web.Settings
import com.pennsieve.models.{ Organization, User }
import com.typesafe.config.Config
import com.pennsieve.core.utilities._
import com.pennsieve.discover.client.publish.PublishClient
import com.pennsieve.discover.client.search.SearchClient
import com.pennsieve.doi.client.doi.DoiClient
import com.pennsieve.utilities.Container
import com.pennsieve.traits.TimeSeriesDBContainer
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.joda.time.DateTime
import com.pennsieve.jobscheduling.clients.generated.jobs.JobsClient
import com.pennsieve.service.utilities.{
  QueueHttpResponder,
  SingleHttpResponder
}

import java.util.concurrent.TimeUnit
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.blackfynn.clients.{ AntiSpamChallengeClient, RecaptchaClient }
import com.pennsieve.audit.middleware.{ AuditLogger, Auditor, GatewayHost }
import org.apache.http.ssl.SSLContexts
import software.amazon.awssdk.services.sns.SnsAsyncClient

import java.util.Date
import scala.concurrent.{ ExecutionContext, Future }

trait ApiSQSContainer { self: Container =>
  val sqs_queue: String = config.as[String]("sqs.queue")
  val sqs_queue_v2: String =
    config.as[String]("sqs.queue_v2")
}

trait ApiSNSContainer { self: Container =>
  val sns_topic: String =
    config.as[Option[String]]("pennsieve.changelog.sns_topic").getOrElse("")
}

object APIContainers {
  type APIContainer = EmailContainer
  type InsecureAPIContainer = APIContainer
    with InsecureContainer
    with InsecureCoreContainer
    with MessageTemplatesContainer
    with DataDBContainer
    with TimeSeriesDBContainer
    with SQSContainer
    with SNSContainer
    with ApiSQSContainer
    with ApiSNSContainer
    with JobSchedulingServiceContainer
  type SecureAPIContainer = APIContainer
    with SecureContainer
    with SecureCoreContainer
    with ChangelogContainer
    with DatasetPublicationStatusContainer

  type SecureContainerBuilderType =
    (User, Organization) => SecureAPIContainer
}

trait BaseBootstrapHelper {
  import APIContainers._

  implicit val ec: ExecutionContext
  implicit val system: ActorSystem

  val config: Config = Settings.config

  val insecureContainer: InsecureAPIContainer
  val secureContainerBuilder: SecureContainerBuilderType

  val customTermsOfServiceClient: CustomTermsOfServiceClient
  val datasetAssetClient: DatasetAssetClient

  lazy val auditLogger: Auditor = {
    val host = config.as[String]("pennsieve.gateway.host")
    new AuditLogger(GatewayHost(host))
  }

  lazy val awsSQSClient: SqsAsyncClient =
    SqsAsyncClient
      .builder()
      .region(Settings.regionV2)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()

  lazy val awsSNSClient: SnsAsyncClient =
    SnsAsyncClient
      .builder()
      .region(Settings.regionV2)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()

  lazy val sqsClient: SQSClient = new SQS(awsSQSClient)
  lazy val snsClient: SNSClient = new SNS(awsSNSClient)

  lazy val ecsClient: ECSTrait = new AWSECS(
    AmazonECSAsyncClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(Settings.region)
      .build()
  )

  lazy val cognitoConfig: CognitoConfig = CognitoConfig(config)
  lazy val cognitoClient: CognitoClient = Cognito(cognitoConfig)

  lazy val publishClient: PublishClient = {
    val host = config.as[String]("pennsieve.discover_service.host")
    val client = new SingleHttpResponder().responder
    PublishClient.httpClient(client, host)
  }

  lazy val searchClient: SearchClient = {
    val host = config.as[String]("pennsieve.discover_service.host")
    val client = new SingleHttpResponder().responder
    SearchClient.httpClient(client, host)
  }

  lazy val doiClient: DoiClient = {
    val host = config.as[String]("pennsieve.doi_service.host")
    val client = new SingleHttpResponder().responder
    DoiClient.httpClient(client, host)
  }

  val objectStore: ObjectStore

  val orcidClientConfig = OrcidClientConfig(config)
  lazy val orcidClient: OrcidClient =
    new OrcidClientImpl(Http(), orcidClientConfig)

  lazy val jobSchedulingServiceClient: JobsClient = insecureContainer.jobsClient

  lazy val urlShortenerClient: UrlShortenerClient =
    new BitlyUrlShortenerClient(Http(), config.as[String]("bitly.access_token"))

  lazy val recaptchaClient: AntiSpamChallengeClient = new RecaptchaClient(
    Http(),
    verifyUrl = config.as[String]("recaptcha.site_verify_url"),
    secretKey = config.as[String]("recaptcha.secret_key")
  )
}

class LocalBootstrapHelper(
)(implicit
  override val ec: ExecutionContext,
  override val system: ActorSystem
) extends BaseBootstrapHelper {

  lazy val objectStore: ObjectStore = new S3ObjectStore()

  override val insecureContainer =
    new InsecureContainer(config) with InsecureCoreContainer
    with LocalEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with LocalSQSContainer with LocalS3Container
    with ApiSQSContainer with LocalSNSContainer with ApiSNSContainer
    with JobSchedulingServiceContainerImpl {
      override val jobSchedulingServiceHost: String =
        config.as[String]("pennsieve.job_scheduling_service.host")
      override val jobSchedulingServiceQueueSize: Int =
        config.as[Int]("pennsieve.job_scheduling_service.queue_size")
      override val jobSchedulingServiceRateLimit: Int =
        config.as[Int]("pennsieve.job_scheduling_service.rate_limit")
    }

  lazy val customTermsOfServiceClient: CustomTermsOfServiceClient =
    new S3CustomTermsOfServiceClient(
      insecureContainer.s3,
      config.as[String]("pennsieve.s3.terms_of_service_bucket_name")
    )

  lazy val datasetAssetClient: DatasetAssetClient = new S3DatasetAssetClient(
    insecureContainer.s3,
    config.as[String]("pennsieve.s3.dataset_assets_bucket_name")
  )

  /** Build a new secure container given a user, an organization, and
    * a list of roles that will override the default roles for that
    * user stored in the database.
    */
  override val secureContainerBuilder: (
    User,
    Organization
  ) => SecureContainer with ChangelogContainer with DatasetPublicationStatusContainer with SecureCoreContainer with LocalEmailContainer = {
    (user: User, organization: Organization) =>
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        user = user,
        organization = organization
      ) with SecureCoreContainer with ChangelogContainer
      with DatasetPublicationStatusContainer with LocalEmailContainer
      with LocalSNSContainer
  }
}

class AWSBootstrapHelper(
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext
) extends BaseBootstrapHelper {

  lazy val objectStore: ObjectStore = new S3ObjectStore()

  override val insecureContainer =
    new InsecureContainer(config) with InsecureCoreContainer
    with AWSEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with AWSSQSContainer with AWSSNSContainer
    with AWSS3Container with ApiSQSContainer with ApiSNSContainer
    with JobSchedulingServiceContainerImpl {
      override val jobSchedulingServiceHost: String =
        config.as[String]("pennsieve.job_scheduling_service.host")
      override val jobSchedulingServiceQueueSize: Int =
        config.as[Int]("pennsieve.job_scheduling_service.queue_size")
      override val jobSchedulingServiceRateLimit: Int =
        config.as[Int]("pennsieve.job_scheduling_service.rate_limit")
    }

  lazy val customTermsOfServiceClient: CustomTermsOfServiceClient =
    new S3CustomTermsOfServiceClient(
      insecureContainer.s3,
      config.as[String]("pennsieve.s3.terms_of_service_bucket_name")
    )

  lazy val datasetAssetClient: DatasetAssetClient = new S3DatasetAssetClient(
    insecureContainer.s3,
    config.as[String]("pennsieve.s3.dataset_assets_bucket_name")
  )

  override val secureContainerBuilder =
    (user: User, organization: Organization) =>
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        user = user,
        organization = organization
      ) with SecureCoreContainer with ChangelogContainer
      with DatasetPublicationStatusContainer with AWSEmailContainer
      with AWSSNSContainer
}
