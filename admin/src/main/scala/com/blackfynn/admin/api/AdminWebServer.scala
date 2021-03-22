// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.http.scaladsl.server.directives.ExecutionDirectives.handleRejections
import akka.http.scaladsl.model.headers.{ HttpOrigin, HttpOriginRange }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }

import com.blackfynn.admin.api.Router.{
  AdminETLServiceContainerImpl,
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.blackfynn.akka.http.{
  HealthCheck,
  HealthCheckService,
  RouteService,
  WebServer
}
import com.blackfynn.auth.middleware.Jwt
import com.blackfynn.aws.email.{ AWSEmailContainer, LocalEmailContainer }
import com.blackfynn.aws.queue.{ AWSSQSContainer, LocalSQSContainer }
import com.blackfynn.aws.s3.{ AWSS3Container, LocalS3Container }
import com.blackfynn.aws.cognito.{ AWSCognitoContainer, LocalCognitoContainer }
import com.blackfynn.clients.S3CustomTermsOfServiceClientContainer
import com.blackfynn.core.utilities._
import com.blackfynn.discover.client.publish.PublishClient
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.service.utilities.SingleHttpResponder
import com.blackfynn.utilities.Container
import com.redis.RedisClientPool
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._

trait AdminContainer { self: Container =>
  val sqs_queue: String = config.as[String]("sqs.queue")
  val jwtConfig = new Jwt.Config {
    override val key = config.as[String]("pennsieve.jwt.key")
  }
}

object AdminWebServer extends App with WebServer with LazyLogging {

  override val actorSystemName: String = "admin"

  override implicit lazy val materializer: ActorMaterializer =
    ActorMaterializer(
      ActorMaterializerSettings(system)
        .withSupervisionStrategy { (exception: Throwable) =>
          logger.error("Unhandled exception thrown", exception)
          Supervision.resume
        }
    )

  val insecureContainer: InsecureResourceContainer =
    if (Settings.isLocal) {
      new InsecureContainer(config) with InsecureCoreContainer
      with LocalEmailContainer with MessageTemplatesContainer
      with LocalSQSContainer with AdminContainer with LocalS3Container
      with S3CustomTermsOfServiceClientContainer
      with AdminETLServiceContainerImpl with LocalCognitoContainer
    } else {
      new InsecureContainer(config) with InsecureCoreContainer
      with AWSEmailContainer with MessageTemplatesContainer with AWSSQSContainer
      with AdminContainer with AWSS3Container
      with S3CustomTermsOfServiceClientContainer
      with AdminETLServiceContainerImpl with AWSCognitoContainer
    }

  def secureContainerBuilder(
    user: User,
    organization: Organization
  ): SecureResourceContainer = {
    if (Settings.isLocal) {
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        _redisClientPool = insecureContainer.redisClientPool,
        user = user,
        organization = organization
      ) with SecureCoreContainer with LocalEmailContainer
      with MessageTemplatesContainer with Router.AdminETLServiceContainerImpl
      with RoleOverrideContainer
    } else {
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        _redisClientPool = insecureContainer.redisClientPool,
        user = user,
        organization = organization
      ) with SecureCoreContainer with AWSEmailContainer
      with MessageTemplatesContainer with LocalS3Container
      with S3CustomTermsOfServiceClientContainer
      with Router.AdminETLServiceContainerImpl with RoleOverrideContainer
    }
  }

  val healthCheck = new HealthCheckService(
    Map(
      "postgres" -> HealthCheck.postgresHealthCheck(insecureContainer.db),
      "redis" -> HealthCheck.redisHealthCheck(insecureContainer.redisClientPool)
    )
  )

  lazy val publishClient: PublishClient = PublishClient.httpClient(
    new SingleHttpResponder().responder,
    Settings.discoverHost
  )

  lazy val redisClientPool: RedisClientPool =
    RedisContainer.poolFromConfig(config)

  override val routeService: RouteService =
    new Router(insecureContainer, secureContainerBuilder, publishClient)

  override lazy val routes: Route =
    Route.seal(routeService.routes ~ healthCheck.routes)

  startServer()
}
