import com.pennsieve.aws.s3.{ S3, S3Trait }
import com.pennsieve.core.utilities.PostgresDatabase
import com.pennsieve.test._
import org.scalatest._
import com.pennsieve.traits.PostgresProfile.api._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.model.StatusCodes.{ InternalServerError, OK }
import com.pennsieve.akka.http._
import com.redis.RedisClientPool
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.circe.java8.time._
import io.circe.syntax._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.Future

class HealthServiceSpec
    extends WordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with PersistantTestContainers
    with RedisDockerContainer
    with PostgresDockerContainer
    with S3DockerContainer {

  var db: Database = _
  var redisPool: RedisClientPool = _
  var s3: S3 = _

  override def afterStart(): Unit = {
    db = postgresContainer.database.forURL

    redisPool = new RedisClientPool(
      redisContainer.containerIpAddress,
      redisContainer.mappedPort
    )

    s3 = new S3(s3Container.s3Client)
    s3.createBucket("my-bucket").right.get
  }

  override def afterAll(): Unit = {
    db.close()
    redisPool.close
    super.afterAll()
  }

  "Health Check" can {
    "GET /health" should {
      "check connection status for serviceHealthy" in {
        val health = new HealthCheckService(
          Map(
            "postgres" -> HealthCheck.postgresHealthCheck(db),
            "s3" -> HealthCheck.s3HealthCheck(s3, List("my-bucket")),
            "redis" -> HealthCheck.redisHealthCheck(redisPool)
          )
        )
        Get("/health") ~> health.routes ~> check {
          status shouldEqual OK
          val health = responseAs[Health]
          health.healthy shouldBe true
          health.serviceHealthy("postgres") shouldBe true
          health.serviceHealthy("s3") shouldBe true
          health.serviceHealthy("redis") shouldBe true
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
