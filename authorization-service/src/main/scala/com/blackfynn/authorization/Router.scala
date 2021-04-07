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

package com.pennsieve.authorization

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive, Route }
import com.pennsieve.akka.http.RouteService
import com.pennsieve.akka.http.directives.AuthorizationDirectives.{
  user,
  AuthorizationContainer
}
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.authorization.routes.{
  AuthenticationRoutes,
  AuthorizationRoutes,
  DiscoverAuthorizationRoutes
}
import com.pennsieve.aws.cognito.CognitoConfig
import com.pennsieve.core.utilities._
import com.pennsieve.utilities._

import scala.concurrent.ExecutionContext

object Router {
  type ResourceContainer = Container
    with AuthorizationContainer
    with JwtContainer
    with TermsOfServiceManagerContainer
    with TokenManagerContainer
}

class Router(
  val container: ResourceContainer
)(implicit
  system: ActorSystem,
  cognitoConfig: CognitoConfig
) extends RouteService {

  implicit val executionContext: ExecutionContext = system.dispatcher

  // use bf-akka-http for authorization:
  val routes: Route =
    user(container, realm = "authentication")(
      container,
      cognitoConfig,
      executionContext
    ) {
      case userContext =>
        logByEnvironment(
          new AuthorizationRoutes(
            user = userContext.user,
            organization = userContext.organization,
            cognitoId = userContext.cognitoId
          )(container, executionContext, system).routes
        )

    } ~ user(container, realm = "authentication")(
      container,
      cognitoConfig,
      executionContext
    ) {
      case userContext =>
        logByEnvironment(
          DiscoverAuthorizationRoutes(user = userContext.user)(
            container,
            executionContext,
            system
          )
        )
    } ~ {
      logByEnvironment(new AuthenticationRoutes(cognitoConfig).routes)
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
