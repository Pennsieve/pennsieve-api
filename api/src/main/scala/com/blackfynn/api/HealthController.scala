package com.blackfynn.api
import java.util.UUID.randomUUID

import cats.data.EitherT
import com.blackfynn.core.utilities.checkOrErrorT
import com.blackfynn.domain.{ CoreError, ServiceError }
import com.blackfynn.helpers.APIContainers.InsecureAPIContainer
import com.blackfynn.helpers.ResultHandlers.OkResult
import com.blackfynn.helpers.{ ErrorLoggingSupport, ParamsSupport }
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{
  ActionResult,
  AsyncResult,
  FutureSupport,
  ScalatraServlet
}
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._
import cats.implicits._
import org.scalatra.swagger.Swagger
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._

import scala.concurrent.{ ExecutionContext, Future }

class HealthController(
  insecureContainer: InsecureAPIContainer,
  asyncExecutor: ExecutionContext
)(implicit
  override val swagger: Swagger
) extends ScalatraServlet
    with PennsieveSwaggerSupport
    with FutureSupport {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  protected val applicationDescription: String = "Core API"

  override val swaggerTag = "Health"

  val getHealthOperation = (apiOperation[Unit]("getHealth")
    summary "performs a health check")

  get("/", operation(getHealthOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {

        dbCheck <- insecureContainer.db
          .run(sql"select 1".as[Int])
          .map(_.contains(1))
          .toEitherT
          .coreErrorToActionResult

        _ <- checkOrErrorT[CoreError](dbCheck)(
          ServiceError("DB connection unavailable")
        ).coreErrorToActionResult

        checkUUID = randomUUID().toString

        _ <- checkOrErrorT[CoreError](
          insecureContainer.redisClientPool
            .withClient { client =>
              {
                client.select(1)
                client.set(checkUUID, checkUUID)
                client.expire(checkUUID, 10)
                client.get(checkUUID)

              }
            }
            .exists(_ == checkUUID)
        )(ServiceError("Redis connection unavailable")).coreErrorToActionResult
      } yield ()

      override val is = result.value.map(OkResult)
    }

  }
}
