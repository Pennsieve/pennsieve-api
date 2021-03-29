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

package com.pennsieve.akka.http

import java.util.UUID.randomUUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directives.{ complete, _ }
import akka.http.scaladsl.server.Route
import com.pennsieve.akka.http.HealthCheck._
import com.pennsieve.aws.s3.S3Trait
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import com.redis.RedisClientPool
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.swagger.annotations.ApiOperation
import javax.ws.rs.Path
import cats.implicits._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

case class Health(currentTime: Long, serviceHealthy: Map[String, Boolean]) {
  def healthy: Boolean = serviceHealthy.values.forall(_ == true)
}

object Health {
  implicit val encoder: Encoder[Health] = deriveEncoder[Health]
  implicit val decoder: Decoder[Health] = deriveDecoder[Health]
}

object HealthCheck {

  type HealthChecker = () => Future[Boolean]

  def redisHealthCheck(
    pool: RedisClientPool
  )(implicit
    executionContext: ExecutionContext
  ): HealthChecker = () => {
    val checkUUID = randomUUID().toString

    Future({
      pool.withClient { client =>
        {
          client.select(0)
          client.set(checkUUID, checkUUID)
          client.expire(checkUUID, 10)
        }
      }
      pool
        .withClient { client =>
          {
            client.select(0)
            client.get(checkUUID)
          }
        }
        .exists(_ == checkUUID)
    })
  }

  def postgresHealthCheck(
    db: Database
  )(implicit
    executionContext: ExecutionContext
  ): HealthChecker = () => {
    db.run(sql"select 1".as[Int])
      .map(_.contains(1))
  }

  def s3HealthCheck(
    s3: S3Trait,
    buckets: List[String]
  )(implicit
    executionContext: ExecutionContext
  ): HealthChecker =
    () =>
      Future
        .traverse(buckets)(
          s3.headBucket(_).map(_ => true).fold(Future.failed, Future.successful)
        )
        .map(_.forall(_ == true))

  def checkAll(
    checkers: Map[String, HealthChecker]
  )(implicit
    executionContext: ExecutionContext
  ): Future[Health] = {
    val names = checkers.keys

    Future
      .traverse(checkers.values)(_().recover { case _ => false })
      .map(
        results => Health(System.currentTimeMillis(), names.zip(results).toMap)
      )
  }
}

class HealthCheckService(
  checkers: Map[String, HealthChecker]
)(implicit
  executionContext: ExecutionContext
) extends RouteService {

  @Path("/health")
  @ApiOperation(
    httpMethod = "GET",
    response = classOf[Health],
    value = "returns an object indicating the health of the service"
  )
  def routes: Route = {
    path("health") {
      onSuccess(checkAll(checkers)) { health =>
        if (health.healthy)
          complete(health)
        else
          complete(InternalServerError, health)
      }
    }
  }
}
