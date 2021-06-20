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

package com.pennsieve.admin.api

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.http.scaladsl.server.directives.ExecutionDirectives.handleRejections
import akka.http.scaladsl.model.headers.{ HttpOrigin, HttpOriginRange }
import com.pennsieve.admin.api.Router.{
  AdminETLServiceContainerImpl,
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.pennsieve.akka.http.{
  HealthCheck,
  HealthCheckService,
  RouteService,
  WebServer
}
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.aws.email.{ AWSEmailContainer, LocalEmailContainer }
import com.pennsieve.aws.queue.{ AWSSQSContainer, LocalSQSContainer }
import com.pennsieve.aws.s3.{ AWSS3Container, LocalS3Container }
import com.pennsieve.aws.cognito.{ AWSCognitoContainer, LocalCognitoContainer }
import com.pennsieve.aws.sns.{ AWSSNSContainer, LocalSNSContainer }
import com.pennsieve.clients.S3CustomTermsOfServiceClientContainer
import com.pennsieve.core.utilities._
import com.pennsieve.discover.client.publish.PublishClient
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.service.utilities.SingleHttpResponder
import com.pennsieve.utilities.Container
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

  val insecureContainer: InsecureResourceContainer =
    if (Settings.isLocal) {
      new InsecureContainer(config) with InsecureCoreContainer
      with LocalEmailContainer with MessageTemplatesContainer
      with LocalSQSContainer with LocalSNSContainer with AdminContainer
      with LocalS3Container with S3CustomTermsOfServiceClientContainer
      with AdminETLServiceContainerImpl with LocalCognitoContainer
    } else {
      new InsecureContainer(config) with InsecureCoreContainer
      with AWSEmailContainer with MessageTemplatesContainer with AWSSQSContainer
      with AWSSNSContainer with AdminContainer with AWSS3Container
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
        user = user,
        organization = organization
      ) with SecureCoreContainer with LocalEmailContainer with LocalSNSContainer
      with MessageTemplatesContainer with Router.AdminETLServiceContainerImpl
    } else {
      new SecureContainer(
        config = insecureContainer.config,
        _db = insecureContainer.db,
        user = user,
        organization = organization
      ) with SecureCoreContainer with AWSEmailContainer
      with MessageTemplatesContainer with LocalS3Container
      with LocalSNSContainer with S3CustomTermsOfServiceClientContainer
      with Router.AdminETLServiceContainerImpl
    }
  }

  val healthCheck = new HealthCheckService(
    Map("postgres" -> HealthCheck.postgresHealthCheck(insecureContainer.db))
  )

  lazy val publishClient: PublishClient = PublishClient.httpClient(
    new SingleHttpResponder().responder,
    Settings.discoverHost
  )

  override val routeService: RouteService =
    new Router(insecureContainer, secureContainerBuilder, publishClient)

  override lazy val routes: Route =
    Route.seal(routeService.routes ~ healthCheck.routes)

  startServer()
}
