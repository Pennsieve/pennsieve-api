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

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.pennsieve.admin.api.Router.{
  InsecureResourceContainer,
  SecureResourceContainer,
  SecureResourceContainerBuilder
}
import com.pennsieve.admin.api.services._
import com.pennsieve.akka.http.directives.AuthorizationDirectives.admin
import com.pennsieve.akka.http.{ RouteService, SwaggerDocService }
import com.pennsieve.aws.email.EmailContainer
import com.pennsieve.aws.cognito.{ CognitoClient, CognitoContainer }
import com.pennsieve.aws.queue.SQSContainer
import com.pennsieve.clients.{
  CustomTermsOfServiceClientContainer,
  JobSchedulingServiceContainer,
  JobSchedulingServiceContainerImpl
}
import com.pennsieve.core.utilities._
import com.pennsieve.discover.client.publish.PublishClient
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.utilities._
import io.swagger.models.Scheme
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContext

object Router {
  type ResourceContainer = Container with CoreContainer with EmailContainer
  type InsecureResourceContainer =
    ResourceContainer
      with InsecureContainer
      with InsecureCoreContainer
      with SQSContainer
      with AdminContainer
      with JobSchedulingServiceContainer
      with CustomTermsOfServiceClientContainer
      with CognitoContainer
  type SecureResourceContainer = ResourceContainer
    with SecureContainer
    with SecureCoreContainer
    with MessageTemplatesContainer
    with JobSchedulingServiceContainer
    with AdminETLServiceContainer

  type SecureResourceContainerBuilder =
    (User, Organization) => SecureResourceContainer

  trait AdminETLServiceContainer { self: Container =>
    val jwtKey: String = config.as[String]("job_scheduling_service.jwt.key")
    val quota: Int = config.as[Int]("job_scheduling_service.quota")
  }

  trait AdminETLServiceContainerImpl
      extends JobSchedulingServiceContainerImpl
      with AdminETLServiceContainer {
    self: Container =>

    override implicit val materializer: ActorMaterializer = ActorMaterializer()

    override val jobSchedulingServiceHost: String =
      config.as[String]("job_scheduling_service.host")
    override val jobSchedulingServiceQueueSize: Int =
      config.as[Int]("job_scheduling_service.queue_size")
    override val jobSchedulingServiceRateLimit: Int =
      config.as[Int]("job_scheduling_service.rate_limit")
  }
}

class Router(
  val insecureContainer: InsecureResourceContainer,
  secureContainerBuilder: SecureResourceContainerBuilder,
  publishClient: PublishClient
)(implicit
  system: ActorSystem,
  materializer: ActorMaterializer
) extends RouteService {

  implicit val executionContext: ExecutionContext = system.dispatcher
  val domainName: Option[String] =
    insecureContainer.config.getAs[String]("domain_name")
  val swaggerHost: String = domainName.getOrElse("localhost:8080")
  val scheme: List[Scheme] =
    if (domainName.isEmpty) List(Scheme.HTTP)
    else List(Scheme.HTTPS)

  val swaggerDocService = new SwaggerDocService(
    host = swaggerHost,
    schemes = scheme,
    apiClasses = Set(
      classOf[OrganizationsService],
      classOf[UserService],
      classOf[PackageService]
    ),
    List()
  )

  val adminOnlyRoutes: Route =
    admin(insecureContainer, realm = "admin")(
      insecureContainer.jwtConfig,
      executionContext
    ) {
      case context =>
        lazy val secureContainer: SecureResourceContainer =
          secureContainerBuilder(context.user, context.organization)
        lazy val organizationsService = new OrganizationsService(
          secureContainer,
          insecureContainer
        )(executionContext, materializer)
        lazy val userService =
          new UserService(secureContainer, insecureContainer)(executionContext)
        lazy val packageService = new PackageService(
          secureContainer,
          insecureContainer
        )(executionContext, materializer)
        lazy val datasetsService =
          new DatasetsService(secureContainer, publishClient)(
            executionContext,
            materializer
          )

        lazy val adminRoutes: Seq[RouteService] =
          Seq(
            organizationsService,
            userService,
            packageService,
            datasetsService
          )

        adminRoutes.tail
          .foldLeft(adminRoutes.head.routes) { (routes, routable) =>
            routes ~ routable.routes
          }
    }

  val routes: Route = adminOnlyRoutes ~ swaggerDocService.routes

}
