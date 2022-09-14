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

package com.pennsieve.api

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._

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

  override val pennsieveSwaggerTag = "Collections"

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
          secureContainer <- getSecureContainer()
          collection <- secureContainer.collectionManager
            .getCollections()
            .coreErrorToActionResult()

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
        secureContainer <- getSecureContainer()
        body <- extractOrErrorT[CreateCollectionRequest](parsedBody)

        newCollection <- secureContainer.collectionManager
          .create(name = body.name)
          .coreErrorToActionResult()

      } yield CollectionDTO(newCollection)

      override val is = result.value.map(CreatedResult)
    }
  }

  val updateCollectionOperation = (apiOperation[ContributorDTO](
    "editCollection"
  )
    summary "changes the name of a collection that belongs to the current organization"
    parameter bodyParam[UpdateCollectionRequest]("body")
      .description("new name of the collection")
    parameter pathParam[String]("id")
      .description("ID of the collection"))

  put("/:id", operation(updateCollectionOperation)) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, CollectionDTO] = for {
        secureContainer <- getSecureContainer()
        body <- extractOrErrorT[UpdateCollectionRequest](parsedBody)

        collectionId <- paramT[Int]("id")

        collection <- secureContainer.collectionManager
          .get(collectionId)
          .coreErrorToActionResult()

        newCollection <- secureContainer.collectionManager
          .update(collection = collection, name = body.name)
          .coreErrorToActionResult()

      } yield CollectionDTO(newCollection)

      override val is = result.value.map(OkResult)
    }
  }

}
