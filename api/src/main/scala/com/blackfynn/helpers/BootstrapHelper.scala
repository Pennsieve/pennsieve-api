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
import akka.stream.ActorMaterializer
import com.amazonaws.ClientConfiguration
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.S3ClientOptions
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.authy.AuthyApiClient
import com.pennsieve.auth.middleware.{ Jwt, UserClaim, UserId }
import com.pennsieve.aws.cognito.{ CognitoClient, CognitoConfig }
import com.pennsieve.aws.s3.AWSS3Container
import com.pennsieve.aws.s3.LocalS3Container
import net.ceedubs.ficus.Ficus._
import com.pennsieve.aws.email.{
  AWSEmailContainer,
  EmailContainer,
  LocalEmailContainer
}
import com.pennsieve.clients._
import com.pennsieve.aws.cognito.Cognito
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
import com.pennsieve.client.NotificationServiceClient
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

import com.pennsieve.audit.middleware.{ AuditLogger, Auditor, GatewayHost }
import com.redis.RedisClientPool
import org.apache.http.ssl.SSLContexts
import java.util.Date
import scala.concurrent.{ ExecutionContext, Future }

trait ApiSQSContainer { self: Container =>
  val sqs_queue: String = config.as[String]("sqs.queue")
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
    with ApiSQSContainer
    with JobSchedulingServiceContainer
  type SecureAPIContainer = APIContainer
    with SecureContainer
    with SecureCoreContainer
    with RoleOverrideContainer

  type SecureContainerBuilderType =
    (User, Organization, List[Jwt.Role]) => SecureAPIContainer
}

trait BaseBootstrapHelper {
  import APIContainers._

  implicit val ec: ExecutionContext
  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer

  val config: Config = Settings.config

  val insecureContainer: InsecureAPIContainer
  val secureContainerBuilder: SecureContainerBuilderType

  val customTermsOfServiceClient: CustomTermsOfServiceClient
  val datasetAssetClient: DatasetAssetClient

  lazy val auditLogger: Auditor = {
    val host = config.as[String]("pennsieve.gateway.host")
    new AuditLogger(GatewayHost(host))
  }

  lazy val authyClient: AuthyApiClient = new AuthyApiClient(
    Settings.authyKey,
    Settings.authyApiUrl,
    !Settings.isProduction
  )

  lazy val awsSQSClient: SqsAsyncClient =
    SqsAsyncClient
      .builder()
      .region(Settings.regionV2)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()

  lazy val sqsClient: SQSClient = new SQS(awsSQSClient)

  lazy val cognitoConfig: CognitoConfig = CognitoConfig(config)
  lazy val cognitoClient: CognitoClient = Cognito(cognitoConfig)

  lazy val modelServiceClient = {

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

    new ModelServiceClient(
      client,
      Settings.modelServiceHost,
      Settings.modelServicePort
    )
  }

  lazy val notificationServiceClient = new NotificationServiceClient(
    Settings.notificationHost,
    Settings.notificationPort
  )

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
}

class LocalBootstrapHelper(
)(implicit
  override val ec: ExecutionContext,
  override val system: ActorSystem,
  override val materializer: ActorMaterializer
) extends BaseBootstrapHelper {

  // Alias needed to prevent NPE from circular reference in JSS container
  val _materializer = materializer

  lazy val objectStore: ObjectStore = new S3ObjectStore(insecureContainer.s3)

  override val insecureContainer =
    new InsecureContainer(config) with InsecureCoreContainer
    with LocalEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with LocalSQSContainer with LocalS3Container
    with ApiSQSContainer with JobSchedulingServiceContainerImpl {
      override implicit val materializer: ActorMaterializer = _materializer
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
    Organization,
    List[Jwt.Role]
  ) => SecureContainer with SecureCoreContainer with LocalEmailContainer with RoleOverrideContainer = {
    (user: User, organization: Organization, roleOverrides: List[Jwt.Role]) =>
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        _redisClientPool = insecureContainer.redisClientPool,
        user = user,
        organization = organization,
        roleOverrides = roleOverrides
      ) with SecureCoreContainer with LocalEmailContainer
      with RoleOverrideContainer
  }
}

class AWSBootstrapHelper(
)(implicit
  override val system: ActorSystem,
  override val ec: ExecutionContext,
  override val materializer: ActorMaterializer
) extends BaseBootstrapHelper {

  // Alias needed to prevent NPE from circular reference in JSS container
  val _materializer = materializer

  lazy val objectStore: ObjectStore = new S3ObjectStore(insecureContainer.s3)

  override val insecureContainer =
    new InsecureContainer(config) with InsecureCoreContainer
    with AWSEmailContainer with MessageTemplatesContainer with DataDBContainer
    with TimeSeriesDBContainer with AWSSQSContainer with AWSS3Container
    with ApiSQSContainer with JobSchedulingServiceContainerImpl {
      override implicit val materializer: ActorMaterializer = _materializer
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
    (user: User, organization: Organization, roleOverrides: List[Jwt.Role]) =>
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        _redisClientPool = insecureContainer.redisClientPool,
        user = user,
        organization = organization,
        roleOverrides = roleOverrides
      ) with SecureCoreContainer with AWSEmailContainer
      with RoleOverrideContainer
}
