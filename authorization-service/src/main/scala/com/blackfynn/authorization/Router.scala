// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.authorization

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive, Route }
import akka.stream.ActorMaterializer
import com.pennsieve.akka.http.RouteService
import com.pennsieve.akka.http.directives.AuthorizationDirectives.{
  session,
  user,
  AuthorizationContainer
}
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.authorization.routes.{
  AuthenticationRoutes,
  AuthorizationRoutes,
  DiscoverAuthorizationRoutes
}
import com.pennsieve.core.utilities._
import com.pennsieve.utilities._

import scala.concurrent.ExecutionContext

object Router {
  type ResourceContainer = Container
    with RedisManagerContainer
    with AuthorizationContainer
    with JwtContainer
    with AuthyContainer
    with TermsOfServiceManagerContainer
    with TokenManagerContainer
}

class Router(
  val container: ResourceContainer
)(implicit
  system: ActorSystem,
  materializer: ActorMaterializer
) extends RouteService {

  implicit val executionContext: ExecutionContext = system.dispatcher

  val authentication =
    new AuthenticationRoutes()(container, executionContext)

  // use bf-akka-http for authorization:
  val routes: Route =
    authentication.routes ~ session(container, realm = "authentication")(
      executionContext
    ) {
      case (session, user, organization) =>
        val authorization = new AuthorizationRoutes(
          user = user,
          organization = organization,
          session = session
        )(container, executionContext, materializer)

        logByEnvironment(authorization.routes)

    } ~ user(container, realm = "authentication")(container, executionContext) {
      case userContext =>
        logByEnvironment(
          DiscoverAuthorizationRoutes(user = userContext.user)(
            container,
            executionContext,
            materializer
          )
        )
    }

  def logByEnvironment[A](routes: Route): Route =
    container.config.getString("environment").toLowerCase match {
      case "local" | "dev" | "test" =>
        logRequestResult("authorization", Logging.InfoLevel)(
          withoutSizeLimit(routes)
        )

      case "prod" | "prod" =>
        withoutSizeLimit(routes)

      case env =>
        throw new Exception(
          s"Cannot initialize for unsupported environment $env"
        )
    }
}
