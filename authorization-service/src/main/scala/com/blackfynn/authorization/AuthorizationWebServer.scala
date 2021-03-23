// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.authorization

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import com.authy.AuthyApiClient
import com.pennsieve.akka.http.{
  HealthCheck,
  HealthCheckService,
  RouteService,
  WebServer
}
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.authorization.Router.ResourceContainer
import com.pennsieve.core.utilities._
import com.pennsieve.utilities.Container
import com.redis.RedisClientPool
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration.FiniteDuration

trait JwtContainer extends Jwt.Config { self: Container =>
  override val key: String = config.as[String]("jwt.key")
  val duration: FiniteDuration = config.as[FiniteDuration]("jwt.duration")
}

trait AuthyContainer { self: Container =>
  val authyKey: String = config.as[String]("authy.key")
  val authyUrl: String = config.as[String]("authy.url")
  val authyDebugMode: Boolean = config.as[String]("environment") != "prod"

  val authy: AuthyApiClient =
    new AuthyApiClient(authyKey, authyUrl, authyDebugMode)
}

object AuthorizationWebServer extends App with WebServer with LazyLogging {

  override val actorSystemName: String = "authorization"

  override implicit lazy val materializer: ActorMaterializer =
    ActorMaterializer(
      ActorMaterializerSettings(system)
        .withSupervisionStrategy { exception: Throwable =>
          logger.error("Unhandled exception thrown", exception)
          Supervision.resume
        }
    )

  val container: ResourceContainer =
    new InsecureContainer(config) with RedisManagerContainer
    with DatabaseContainer with SessionManagerContainer
    with OrganizationManagerContainer with JwtContainer with AuthyContainer
    with TermsOfServiceManagerContainer with TokenManagerContainer

  val healthCheck = new HealthCheckService(
    Map(
      "postgres" -> HealthCheck.postgresHealthCheck(container.db),
      "redis" -> HealthCheck.redisHealthCheck(container.redisClientPool)
    )
  )

  override val routeService: RouteService = new Router(container)

  override lazy val routes: Route =
    Route.seal(routeService.routes ~ healthCheck.routes)

  startServer()
}
