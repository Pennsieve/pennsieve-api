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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.checkAndNormalizeInitial
import com.pennsieve.db.{ ContributorMapper, DatasetContributorMapper }
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.managers.{ ContributorManager, UserManager }
import com.pennsieve.models.{ Contributor, Degree, Organization, User }
import com.pennsieve.traits.PostgresProfile.api.Database
import org.apache.commons.validator.routines.EmailValidator

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Mirrors validation in `ContributorManager`:
  *   - email must be syntactically valid (EmailValidator)
  *   - email uniqueness within org is **case-insensitive** (the real impl
  *     uses `_.email.toLowerCase === email.toLowerCase`)
  *   - first/last/email mandatory iff no `userId`
  *   - if a `userId` is provided, the user must already be a member of this
  *     organization
  *   - on `getContributor`, the contributor row is merged with user fields
  *     when `userId` is set (firstName/lastName/email come from user; orcid
  *     also comes from user when present)
  */
class FakeContributorManager(
  state: InMemoryState,
  override val organization: Organization,
  override val actor: User
) extends ContributorManager {

  def db: Database =
    sys.error(
      "FakeContributorManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )

  def contributorMapper: ContributorMapper =
    sys.error("FakeContributorManager: contributorMapper not used in fakes.")

  def userManager: UserManager = new FakeUserManager(state)

  override implicit def datasetContributor: DatasetContributorMapper =
    sys.error(
      "FakeContributorManager: datasetContributor mapper not used in fakes."
    )

  private val emailValidator = EmailValidator.getInstance()

  private def userInOrg(userId: Int): Option[User] =
    if (state.orgUserPermissions.contains((organization.id, userId)))
      state.users.get(userId)
    else None

  private def userByEmailInOrg(email: String): Option[User] =
    state.users.values.find { u =>
      u.email.equalsIgnoreCase(email) &&
      state.orgUserPermissions.contains((organization.id, u.id))
    }

  // Mirrors ContributorTable.getContributorByEmail: matches when the
  // contributor's own email matches OR the contributor's linked user's email
  // matches (the real query is a left-join on UserMapper).
  private def existsByEmail(email: String): Boolean =
    state.contributors.exists {
      case ((orgId, _), c) =>
        if (orgId != organization.id) false
        else
          c.email.exists(_.equalsIgnoreCase(email)) ||
          c.userId
            .flatMap(state.users.get)
            .exists(_.email.equalsIgnoreCase(email))
    }

  override def emailExists(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] =
    EitherT.rightT(existsByEmail(email))

  override def create(
    firstName: String,
    lastName: String,
    email: String,
    middleInitial: Option[String],
    degree: Option[Degree],
    orcid: Option[String] = None,
    userId: Option[Int] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] = {
    val trimmedEmail = email.trim

    val maybeUserByEmail =
      if (trimmedEmail.isEmpty) None else userByEmailInOrg(trimmedEmail)
    val maybeUserByUserId = userId.flatMap(userInOrg)

    val program: Either[CoreError, (Contributor, Option[User])] = for {
      _ <- if (existsByEmail(trimmedEmail) && trimmedEmail.nonEmpty)
        Left(PredicateError("email must be unique"): CoreError)
      else Right(())

      _ <- if ((userId.isDefined && maybeUserByUserId.isDefined) || userId.isEmpty)
        Right(())
      else Left(PredicateError("userID Not found"): CoreError)

      _ <- if (firstName.trim.nonEmpty || userId.isDefined) Right(())
      else
        Left(
          PredicateError(
            "first name of contributor must not be empty if not already a user"
          ): CoreError
        )

      _ <- if (lastName.trim.nonEmpty || userId.isDefined) Right(())
      else
        Left(
          PredicateError(
            "last name of contributor must not be empty if not already a user"
          ): CoreError
        )

      _ <- if (trimmedEmail.nonEmpty || userId.isDefined) Right(())
      else
        Left(
          PredicateError(
            "email of contributor must not be empty if not already a user"
          ): CoreError
        )

      _ <- if (emailValidator.isValid(trimmedEmail) || trimmedEmail.isEmpty)
        Right(())
      else Left(PredicateError("improper email format"): CoreError)

      _ <- if (maybeUserByUserId.map(_.id) == maybeUserByEmail.map(_.id) ||
        maybeUserByUserId.isEmpty || maybeUserByEmail.isEmpty)
        Right(())
      else
        Left(
          PredicateError("email and userId belong to different users"): CoreError
        )

      middleInit <- checkAndNormalizeInitial(middleInitial)
    } yield {
      val contributorFirstName =
        if (firstName.trim.isEmpty) None else Some(firstName.trim)
      val contributorLastName =
        if (lastName.trim.isEmpty) None else Some(lastName.trim)
      val contributorEmail =
        if (trimmedEmail.isEmpty) None else Some(trimmedEmail)

      val actualUser = maybeUserByEmail.orElse(maybeUserByUserId)

      val contributor = Contributor(
        firstName = contributorFirstName,
        lastName = contributorLastName,
        email = contributorEmail,
        middleInitial = middleInit,
        degree = degree,
        orcid = orcid,
        userId = actualUser.map(_.id),
        id = state.newId()
      )
      state.contributors.put((organization.id, contributor.id), contributor)
      (contributor, actualUser)
    }

    EitherT.fromEither[Future](program)
  }

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Contributor] =
    state.contributors.get((organization.id, id)) match {
      case Some(c) => EitherT.rightT(c)
      case None => EitherT.leftT(NotFound(s"Contributor ($id)"))
    }

  override def getContributor(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    state.contributors.get((organization.id, id)) match {
      case Some(c) =>
        EitherT.rightT((c, c.userId.flatMap(state.users.get)))
      case None => EitherT.leftT(NotFound(s"Contributor: $id"))
    }

  override def getContributors(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Contributor, Option[User])]] =
    EitherT.rightT(
      state.contributors
        .collect {
          case ((orgId, _), c) if orgId == organization.id =>
            (c, c.userId.flatMap(state.users.get))
        }
        .toSeq
        .sortBy(_._1.id)
    )

  override def updateInfo(
    firstName: Option[String],
    lastName: Option[String],
    orcid: Option[String],
    middleInitial: Option[String],
    degree: Option[Degree],
    contributorId: Int,
    overwriteOrcId: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    for {
      contributorAndUser <- getContributor(contributorId)
      (contributor, user) = contributorAndUser

      existingUserOrcid = user
        .map(_.orcidAuthorization)
        .getOrElse(None)
        .map(_.orcid)

      middleInit <- checkAndNormalizeInitial(middleInitial).toEitherT[Future]

      _ <- FutureEitherHelpers.assert[CoreError](
        (firstName.exists(_.trim.nonEmpty) && user.isEmpty) || firstName.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user. Only the user can change their own first name"
        )
      )

      _ <- FutureEitherHelpers.assert[CoreError](
        (lastName.exists(_.trim.nonEmpty) && user.isEmpty) || lastName.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user. Only the user can change their own last name"
        )
      )

      _ <- FutureEitherHelpers.assert[CoreError](
        (orcid.exists(_.trim.nonEmpty) &&
          (user.isEmpty || existingUserOrcid.isEmpty)) || orcid.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user and has defined his ORCID. Only the user can change this value"
        )
      )

      orcidToUse = if (overwriteOrcId) orcid.map(_.trim)
      else
        user
          .map(_.orcidAuthorization)
          .getOrElse(None)
          .map(_.orcid)
          .orElse(orcid)
          .orElse(contributor.orcid)

      firstNameToUse = user
        .map(_.firstName)
        .orElse(firstName)
        .orElse(contributor.firstName)
      lastNameToUse = user
        .map(_.lastName)
        .orElse(lastName)
        .orElse(contributor.lastName)

      updated = contributor.copy(
        firstName = firstNameToUse,
        lastName = lastNameToUse,
        middleInitial = middleInit,
        degree = degree,
        orcid = orcidToUse
      )
      _ = state.contributors.put((organization.id, contributorId), updated)
    } yield (updated, user)
}
