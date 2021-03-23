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

package com.pennsieve.domain

import cats.data.EitherT

import com.pennsieve.managers.{ OrganizationManager, RedisManager, UserManager }
import com.pennsieve.models.{ Organization, User }

import scala.concurrent.{ ExecutionContext, Future }

object Sessions {
  sealed trait SessionType
  case class APISession(token: String) extends SessionType
  case object BrowserSession extends SessionType
  case object TemporarySession extends SessionType

  def sessionKey(uuid: String): String = s"session:$uuid"

  object Session {
    def apply(
      user: User,
      `type`: SessionType,
      organization: Organization
    ): Session = {
      new Session(
        java.util.UUID.randomUUID.toString,
        user.nodeId,
        `type`,
        organization.nodeId
      )
    }
  }

  case class Session(
    uuid: String,
    userId: String,
    `type`: SessionType,
    organizationId: String
  ) {
    val key: String = sessionKey(uuid)

    def user(
    )(implicit
      userManager: UserManager,
      ec: ExecutionContext
    ): EitherT[Future, CoreError, User] =
      userManager.getByNodeId(userId)

    def organization(
    )(implicit
      organizationManager: OrganizationManager,
      ec: ExecutionContext
    ): EitherT[Future, CoreError, Organization] =
      organizationManager.getByNodeId(organizationId)

    def refresh(
      ttl: Int
    )(implicit
      redisManager: RedisManager
    ): Either[CoreError, Unit] = {
      val result: Boolean = redisManager.expire(key, ttl)

      if (result) Right(())
      else Left(Error("Failed to update refresh session ttl"))
    }

    def isAPISession: Boolean = {
      `type` match {
        case APISession(_) => true
        case _ => false
      }
    }

    def isBrowserSession: Boolean = {
      `type` match {
        case BrowserSession => true
        case _ => false
      }
    }

  }

}
