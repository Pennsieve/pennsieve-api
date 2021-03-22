// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import cats.data.EitherT
import cats.implicits._
import com.blackfynn.dtos._
import com.blackfynn.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.blackfynn.helpers.ResultHandlers._
import com.blackfynn.helpers.either.EitherTErrorHandler.implicits._

import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }

case class CreateCollectionRequest(name: String)

case class UpdateCollectionRequest(name: String)

class CollectionsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "Collections"

  get(
    "/",
    operation(
      apiOperation[List[ContributorDTO]]("getCollections")
        summary "get the collections that belong to an organization"
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[CollectionDTO]] =
        for {
          secureContainer <- getSecureContainer

          collection <- secureContainer.collectionManager
            .getCollections()
            .coreErrorToActionResult

        } yield collection.map(CollectionDTO(_))

      override val is = result.value.map(OkResult(_))
    }
  }

  post(
    "/",
    operation(
      apiOperation[ContributorDTO]("createCollection")
        summary "creates a new collection that belongs to the current organization"
        parameters (
          bodyParam[CreateCollectionRequest]("body")
            .description("name of the new collection")
          )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CollectionDTO] = for {
        secureContainer <- getSecureContainer

        body <- extractOrErrorT[CreateCollectionRequest](parsedBody)

        newCollection <- secureContainer.collectionManager
          .create(name = body.name)
          .coreErrorToActionResult

      } yield CollectionDTO(newCollection)

      override val is = result.value.map(CreatedResult)
    }
  }

  put(
    "/:id",
    operation(
      apiOperation[ContributorDTO]("editCollection")
        summary "changes the name of a collection that belongs to the current organization"
        parameters (
          queryParam[Int]("id")
            .description("identifier of the collection"),
          bodyParam[UpdateCollectionRequest]("body")
            .description("new name of the collection"),
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CollectionDTO] = for {
        secureContainer <- getSecureContainer

        body <- extractOrErrorT[UpdateCollectionRequest](parsedBody)

        collectionId <- paramT[Int]("id")

        collection <- secureContainer.collectionManager
          .get(collectionId)
          .coreErrorToActionResult

        newCollection <- secureContainer.collectionManager
          .update(collection = collection, name = body.name)
          .coreErrorToActionResult

      } yield CollectionDTO(newCollection)

      override val is = result.value.map(OkResult)
    }
  }

}
