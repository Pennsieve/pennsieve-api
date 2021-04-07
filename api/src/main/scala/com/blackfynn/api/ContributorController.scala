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
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.OrcidClient
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models._
import org.json4s.JValue
import org.json4s.JsonAST.JNothing
import org.scalatra._
import org.scalatra.swagger.Swagger

import scala.concurrent.{ ExecutionContext, Future }

case class CreateContributorRequest(
  firstName: String,
  lastName: String,
  email: String,
  middleInitial: Option[String] = None,
  degree: Option[Degree] = None,
  orcid: Option[String] = None,
  userId: Option[Int] = None
)

case class UpdateContributorRequest(
  firstName: Option[String] = None,
  lastName: Option[String] = None,
  middleInitial: Option[String],
  degree: Option[Degree] = None,
  orcid: Option[String] = None
)

class ContributorsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  asyncExecutor: ExecutionContext,
  orcidClient: OrcidClient
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  implicit class JValueExtended(value: JValue) {
    def hasField(childString: String): Boolean =
      (value \ childString) != JNothing
  }

  override protected implicit def executor: ExecutionContext = asyncExecutor

  override val swaggerTag = "Contributors"

  get(
    "/",
    operation(
      apiOperation[List[ContributorDTO]]("getContributors")
        summary "get the contributors that belong to an organization"
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, Seq[ContributorDTO]] = for {
        secureContainer <- getSecureContainer
        user = secureContainer.user

        contributorsAndUsers <- secureContainer.contributorsManager
          .getContributors()
          .coreErrorToActionResult

      } yield contributorsAndUsers.map(ContributorDTO(_))

      override val is = result.value.map(OkResult(_))
    }
  }

  post(
    "/",
    operation(
      apiOperation[ContributorDTO]("createContributor")
        summary "creates a new contributor that belongs to the current organization"
        parameters (
          bodyParam[CreateContributorRequest]("body")
            .description("name and properties of the new contributor")
          )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ContributorDTO] = for {
        secureContainer <- getSecureContainer

        body <- extractOrErrorT[CreateContributorRequest](parsedBody)

        orcid = body.orcid match {
          case None => None
          case Some(orcid) if orcid.trim.isEmpty => None
          case Some(orcid) => Some(orcid)
        }

        _ <- orcidClient.verifyOrcid(orcid).toEitherT.orNotFound

        newContributorAndUser <- secureContainer.contributorsManager
          .create(
            firstName = body.firstName,
            lastName = body.lastName,
            email = body.email,
            middleInitial = body.middleInitial,
            degree = body.degree,
            orcid = orcid,
            userId = body.userId
          )
          .coreErrorToActionResult

      } yield ContributorDTO(newContributorAndUser)

      override val is = result.value.map(CreatedResult)
    }
  }

  get(
    "/:id",
    operation(
      apiOperation[ContributorDTO]("getContributor")
        summary "gets a contributor"
        parameters (
          pathParam[Int]("id").description("contributor id")
        )
    )
  ) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, ContributorDTO] = for {
        secureContainer <- getSecureContainer
        contributorId <- paramT[Int]("id")

        contributorAndUser <- secureContainer.contributorsManager
          .getContributor(contributorId)
          .coreErrorToActionResult

      } yield ContributorDTO(contributorAndUser)

      override val is = result.value.map(OkResult(_))
    }
  }

  put(
    "/:id",
    operation(
      apiOperation[ContributorDTO]("updateContributor")
        summary "updates a contributor that belongs to the current organization"
        parameters (
          pathParam[Int]("id").description("contributor id"),
          bodyParam[UpdateContributorRequest]("body")
            .description("contributor's properties to be updated")
      )
    )
  ) {
    new AsyncResult {
      val result: EitherT[Future, ActionResult, ContributorDTO] = for {
        secureContainer <- getSecureContainer

        contributorId <- paramT[Int]("id")
        hasOrcid = parsedBody.hasField("orcid")
        body <- extractOrErrorT[UpdateContributorRequest](parsedBody)
        orcid = body.orcid.filter(_.nonEmpty)

        _ <- orcidClient.verifyOrcid(orcid).toEitherT.orNotFound

        newContributorAndUser <- secureContainer.contributorsManager
          .updateInfo(
            firstName = body.firstName,
            lastName = body.lastName,
            middleInitial = body.middleInitial,
            degree = body.degree,
            orcid = orcid,
            contributorId = contributorId,
            overwriteOrcId = hasOrcid
          )
          .coreErrorToActionResult

      } yield ContributorDTO(newContributorAndUser)

      override val is = result.value.map(OkResult(_))
    }
  }
}
