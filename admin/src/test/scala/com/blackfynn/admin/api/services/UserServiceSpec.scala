// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.admin.api.services

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.StatusCodes.OK
import cats.syntax.option._
import com.blackfynn.aws.cognito.MockCognito
import com.blackfynn.models.{ DBPermission, User, UserInvite }
import com.blackfynn.models.DBPermission.Owner
import com.blackfynn.test.helpers.EitherValue._
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
            .head == email
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
