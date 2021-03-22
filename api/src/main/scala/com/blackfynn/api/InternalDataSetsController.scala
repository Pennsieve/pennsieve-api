package com.blackfynn.api

import cats.data._
import cats.implicits._
import com.blackfynn.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.blackfynn.helpers.ResultHandlers.OkResult
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import scala.concurrent._

class InternalDataSetsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with InternalAuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "DataSetsInternal"

  val touchDataSetOperation: OperationBuilder = (apiOperation[Unit](
    "touchDataSet"
  )
    summary "touch the updatedAt timestamp for a data set (Internal Use Only)"
    parameters (
      pathParam[Int]("id").description("data set id")
    ))

  post("/:id/touch", operation(touchDataSetOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Unit] = for {
        datasetId <- paramT[Int]("id")

        secureContainer <- getSecureContainer

        _ <- secureContainer.datasetManager
          .touchUpdatedAtTimestamp(datasetId)
          .coreErrorToActionResult

      } yield ()

      override val is = result.value.map(OkResult)
    }
  }

}
