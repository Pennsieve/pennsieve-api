// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.blackfynn.admin.api.Router.{
  InsecureResourceContainer,
  SecureResourceContainer,
  SecureResourceContainerBuilder
}
import com.blackfynn.admin.api.services._
import com.blackfynn.akka.http.directives.AuthorizationDirectives.admin
import com.blackfynn.akka.http.{ RouteService, SwaggerDocService }
import com.blackfynn.aws.email.EmailContainer
import com.blackfynn.aws.cognito.{ CognitoClient, CognitoContainer }
import com.blackfynn.aws.queue.SQSContainer
import com.blackfynn.clients.{
  CustomTermsOfServiceClientContainer,
  JobSchedulingServiceContainer,
  JobSchedulingServiceContainerImpl
}
import com.blackfynn.core.utilities._
import com.blackfynn.discover.client.publish.PublishClient
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.utilities._
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
