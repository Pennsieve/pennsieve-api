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

import com.pennsieve.aws.s3.{ S3, S3Trait }
import com.pennsieve.core.utilities.PostgresDatabase
import com.pennsieve.test._
import com.pennsieve.traits.PostgresProfile.api._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.model.StatusCodes.{ InternalServerError, OK }
import com.pennsieve.akka.http._
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.circe.java8.time._
import io.circe.syntax._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

class HealthCheckSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with PersistantTestContainers
    with PostgresDockerContainer
    with S3DockerContainer {

  var db: Database = _
  var s3: S3 = _

  override def afterStart(): Unit = {
    db = postgresContainer.database.forURL
    s3 = new S3(s3Container.s3Client)
    s3.createBucket("my-bucket").right.get
  }

  override def afterAll(): Unit = {
    db.close()
    super.afterAll()
  }

  "Health Check" can {
    "GET /health" should {
      "check connection status for serviceHealthy" in {
        val health = new HealthCheckService(
          Map(
            "postgres" -> HealthCheck.postgresHealthCheck(db),
            "s3" -> HealthCheck.s3HealthCheck(s3, List("my-bucket"))
          )
        )
        Get("/health") ~> health.routes ~> check {
          status shouldEqual OK
          val health = responseAs[Health]
          health.healthy shouldBe true
          health.serviceHealthy("postgres") shouldBe true
          health.serviceHealthy("s3") shouldBe true
        }
      }

      "fail when a check fails" in {
        val health = new HealthCheckService(
          Map("failureService" -> (() => Future.successful(false)))
        )
        Get("/health") ~> health.routes ~> check {
          status shouldEqual InternalServerError
          val health = responseAs[Health]
          health.healthy shouldBe false
          health.serviceHealthy("failureService") shouldBe false
        }
      }

      "fail when a check errors" in {
        val health = new HealthCheckService(
          Map("errorService" -> (() => Future.failed(new Exception)))
        )
        Get("/health") ~> health.routes ~> check {
          status shouldEqual InternalServerError
          val health = responseAs[Health]
          health.healthy shouldBe false
          health.serviceHealthy("errorService") shouldBe false
        }
      }

      "fail when it does not have S3 bucket access" in {
        val health = new HealthCheckService(
          Map(
            "s3" -> HealthCheck
              .s3HealthCheck(s3, List("my-bucket", "another-bucket"))
          )
        )
        Get("/health") ~> health.routes ~> check {
          status shouldEqual InternalServerError
          val health = responseAs[Health]
          health.healthy shouldBe false
          health.serviceHealthy("s3") shouldBe false
        }
      }
    }
  }
}
