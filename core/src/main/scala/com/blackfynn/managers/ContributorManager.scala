// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import cats.data._
import cats.implicits._
import com.blackfynn.core.utilities.{ checkAndNormalizeInitial }
import com.blackfynn.core.utilities.FutureEitherHelpers
import com.blackfynn.core.utilities.FutureEitherHelpers.assert
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.db._
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.domain.{ CoreError, NotFound, PredicateError }
import org.apache.commons.validator.routines.EmailValidator

import scala.concurrent.{ ExecutionContext, Future }

class ContributorManager(
  val db: Database,
  val actor: User,
  val contributorMapper: ContributorMapper,
  val userManager: UserManager
) {

  val organization: Organization = contributorMapper.organization

  implicit val datasetContributor: DatasetContributorMapper =
    new DatasetContributorMapper(organization)

  def emailExists(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Boolean] = {

    db.run(contributorMapper.getContributorByEmail(email).exists.result)
      .toEitherT
  }

  def create(
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

    val emailValidator = EmailValidator.getInstance()

    for {
      exists <- emailExists(email.trim)

      _ <- FutureEitherHelpers.assert(!exists || email.trim.isEmpty)(
        PredicateError("email must be unique")
      )

      maybeUserByEmail <- db
        .run(
          OrganizationsMapper
            .getUserByEmailInOrganization(email, organization.id)
        )
        .toEitherT

      maybeUserByUserId <- userId match {
        case Some(userId) =>
          db.run(
              OrganizationsMapper
                .getUserByIntIdInOrganization(userId, organization.id)
            )
            .toEitherT
        case None => EitherT.rightT[Future, CoreError](None: Option[User])
      }

      _ <- FutureEitherHelpers.assert(
        (userId.isDefined && maybeUserByUserId.isDefined) || userId.isEmpty
      )(PredicateError("userID Not found"))

      _ <- FutureEitherHelpers.assert(
        firstName.trim.nonEmpty || userId != None
      )(
        PredicateError(
          "first name of contributor must not be empty if not already a user"
        )
      )

      _ <- FutureEitherHelpers.assert(lastName.trim.nonEmpty || userId != None)(
        PredicateError(
          "last name of contributor must not be empty if not already a user"
        )
      )

      _ <- FutureEitherHelpers.assert(email.trim.nonEmpty || userId != None)(
        PredicateError(
          "email of contributor must not be empty if not already a user"
        )
      )

      _ <- FutureEitherHelpers.assert(
        emailValidator.isValid(email.trim) || email.trim.isEmpty
      )(PredicateError("improper email format"))

      _ <- FutureEitherHelpers.assert(
        maybeUserByUserId.map(_.id) === maybeUserByEmail.map(_.id)
          || maybeUserByUserId.isEmpty || maybeUserByEmail.isEmpty
      )(PredicateError("email and userId belong to different users"))

      middleInit <- checkAndNormalizeInitial(middleInitial).toEitherT[Future]

      actualUser = if (maybeUserByEmail.isDefined) {
        maybeUserByEmail
      } else {
        maybeUserByUserId
      }

      contributorFirstName = if (firstName.trim.isEmpty) {
        None
      } else {
        Some(firstName.trim)
      }

      contributorLastName = if (lastName.trim.isEmpty) {
        None
      } else {
        Some(lastName.trim)
      }
      contributorEmail = if (email.trim.isEmpty) {
        None
      } else {
        Some(email.trim)
      }

      row = Contributor(
        firstName = contributorFirstName,
        lastName = contributorLastName,
        email = contributorEmail,
        middleInitial = middleInit,
        degree = degree,
        orcid = orcid,
        userId = actualUser.map(_.id)
      )

      createdContributor = (contributorMapper returning contributorMapper) += row

      contributor <- db.run(createdContributor.transactionally).toEitherT
    } yield (contributor, actualUser)
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Contributor] =
    db.run(contributorMapper.filter(_.id === id).result.headOption)
      .whenNone(NotFound(s"Contributor ($id)"))

  def getOrCreateContributorFromUser(
    user: User
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, User)] = {

    for {
      contributorAndUser <- getContributorByUserId(user.id).recoverWith {
        case _: NotFound =>
          getContributorByEmail(user.email)
            .recoverWith {
              case _: NotFound =>
                create(
                  user.firstName,
                  user.lastName,
                  user.email,
                  None,
                  None,
                  user.orcidAuthorization.map(_.orcid),
                  Some(user.id)
                )
            }
      }

      (contributor, maybeUser) = contributorAndUser

      _ = if (maybeUser.isEmpty) {
        db.run(
            contributorMapper
              .filter(_.id === contributor.id)
              .map(c => c.userId)
              .update(Some(user.id))
          )
          .toEitherT
      }
    } yield (contributor, user)
  }

  def getContributorByUserId(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    db.run(contributorMapper.getContributorByUserId(id).result.headOption)
      .whenNone(NotFound(s"Contributor ($id)"))

  def getContributorByEmail(
    email: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    db.run(contributorMapper.getContributorByEmail(email).result.headOption)
      .whenNone(NotFound(s"Contributor: $email"))

  def getContributor(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    db.run(contributorMapper.getContributor(id).result.headOption)
      .whenNone(NotFound(s"Contributor: $id"))

  def getContributors(
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[(Contributor, Option[User])]] =
    db.run(contributorMapper.getContributors().result).toEitherT

  def upgradeContributor(
    email: String,
    userId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, (Contributor, Option[User])] =
    for {
      emailContributor <- db
        .run(
          contributorMapper
            .filter(_.email.toLowerCase === email.toLowerCase)
            .result
            .headOption
        )
        .whenNone(NotFound(s"Contributor with email ($email)"))

      _ <- db
        .run(
          contributorMapper
            .filter(_.id === emailContributor.id)
            .map(c => c.userId)
            .update(Some(userId))
        )
        .toEitherT
      updatedContributor <- get(emailContributor.id)
      user <- updatedContributor.userId.traverse(userManager.get(_))
    } yield (updatedContributor, user)

  def updateInfo(
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

      _ <- FutureEitherHelpers.assert(
        (firstName
          .map(_.trim.nonEmpty)
          .getOrElse(false) && user.isEmpty) || firstName.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user. Only the user can change their own first name"
        )
      )

      _ <- FutureEitherHelpers.assert(
        (lastName
          .map(_.trim.nonEmpty)
          .getOrElse(false) && user.isEmpty) || lastName.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user. Only the user can change their own last name"
        )
      )

      _ <- FutureEitherHelpers.assert(
        (orcid
          .map(_.trim.nonEmpty)
          .getOrElse(false) && (user.isEmpty || existingUserOrcid.isEmpty)) || orcid.isEmpty
      )(
        PredicateError(
          "this contributor is a registered user and has defined his ORCID. Only the user can change this value"
        )
      )

      orcidToUse = if (overwriteOrcId) {
        orcid.map(_.trim)
      } else {
        user
          .map(_.orcidAuthorization)
          .getOrElse(None)
          .map(_.orcid)
          .orElse(orcid)
          .orElse(contributor.orcid)
      }

      firstNameToUse = user
        .map(_.firstName)
        .orElse(firstName)
        .orElse(contributor.firstName)

      lastNameToUse = user
        .map(_.lastName)
        .orElse(lastName)
        .orElse(contributor.lastName)

      _ <- db
        .run(
          contributorMapper
            .filter(_.id === contributorId)
            .map(
              c => (c.firstName, c.lastName, c.middleInitial, c.degree, c.orcid)
            )
            .update(
              (firstNameToUse, lastNameToUse, middleInit, degree, orcidToUse)
            )
        )
        .toEitherT

      updatedContributor <- get(contributorId)

      user <- updatedContributor.userId.map { userId =>
        userManager
          .get(userId)
      }.sequence

    } yield (updatedContributor, user)
}
