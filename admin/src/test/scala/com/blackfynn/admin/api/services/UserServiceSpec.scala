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

package com.pennsieve.admin.api.services

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.StatusCodes.OK
import cats.syntax.option._
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.models.{ DBPermission, User, UserInvite }
import com.pennsieve.models.DBPermission.Owner
import com.pennsieve.test.helpers.EitherValue._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
import io.circe.syntax._
import org.scalatest.EitherValues._

class UserServiceSpec extends AdminServiceSpec {

  "user service" should {

    val email = "test@test.com"
    val firstName = "Fynn"
    val lastName = "Blackwell"
    val ownerEmail = "testowner@test.com"
    val body = InviteRequest(
      inviterFullName = "Test Tester",
      organizationId = "testorg",
      email = email,
      firstName = firstName,
      lastName = lastName,
      isOwner = false
    )

    "create new user token" in {

      val payload =
        body.copy(organizationId = organizationOne.nodeId).asJson.some

      testRequest(POST, "/users/invite", payload, session = adminSession) ~>
        routes ~> check {
        status shouldEqual OK
        val invites = responseAs[List[UserInvite]]
        assert(invites.map(_.email).contains(email))

        val createdInvite: Seq[UserInvite] =
          testDIContainer.userInviteManager.getByEmail(email).await.right.value
        assert(createdInvite.head.email == email)

        assert(
          testDIContainer.cognitoClient
            .asInstanceOf[MockCognito]
            .sentInvites
            .head
            .address == email
        )
      }
    }

    "create new user owner token" in {

      val payload = body
        .copy(
          isOwner = true,
          email = ownerEmail,
          organizationId = organizationOne.nodeId
        )
        .asJson
        .some

      testRequest(POST, "/users/invite", payload, session = adminSession) ~>
        routes ~> check {
        status shouldEqual OK
        val invites = responseAs[List[UserInvite]]
        assert(invites.map(_.email).contains(ownerEmail))

        val createdInvite: Seq[UserInvite] = testDIContainer.userInviteManager
          .getByEmail(ownerEmail)
          .await
          .right
          .value
        assert(createdInvite.head.permission == Owner)
        assert(createdInvite.head.email == ownerEmail)
      }
    }

    "allow inviting an existing user as an owner" in {

      val payload = body
        .copy(
          isOwner = true,
          email = nonAdmin.email,
          organizationId = organizationOne.nodeId
        )
        .asJson
        .some

      organizationManager
        .addUser(organizationOne, nonAdmin, DBPermission.Owner)
        .await
        .value
      testRequest(POST, "/users/invite", payload, session = adminSession) ~>
        routes ~> check {
        status shouldEqual OK
        val invites = responseAs[List[User]]

        assert(invites.map(_.email).contains(nonAdmin.email))

        val (owners, _) = organizationManager
          .getOwnersAndAdministrators(organizationOne)
          .await
          .value

        assert(owners.exists(n => n.nodeId == nonAdmin.nodeId))
      }

      // Should not send any Cognito requests
      assert(
        testDIContainer.cognitoClient
          .asInstanceOf[MockCognito]
          .sentInvites
          .isEmpty
      )
    }
  }
}
