// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.test.helpers

import cats.data._

import com.pennsieve.core.utilities.SecureCoreContainer
import com.pennsieve.core.utilities.{ SecureContainer, SecureCoreContainer }
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.managers.SecureOrganizationManager
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class TestableOrganizationManager(
  checkSchema: Boolean,
  db: Database,
  user: User
) extends SecureOrganizationManager(db, user) {

  override def schemaExists(
    organization: Organization
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, String] = {
    if (checkSchema) {
      EitherT(
        Future.successful(Left(NotFound("error!")): Either[CoreError, String])
      )
    } else {
      EitherT(
        Future
          .successful(Right(organization.schemaId): Either[CoreError, String])
      )
    }
  }
}
