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

package com.pennsieve.managers

import java.time.Duration

import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.db.UserMapper
import com.pennsieve.domain.PredicateError

import com.pennsieve.models._
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.EitherValues._
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class UserManagerSpec extends BaseManagerSpec {

  "updateUser" should "update an existing user node" in {
    val user = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = "test@test.com",
      firstName = "",
      middleInitial = None,
      lastName = "",
      degree = None,
      credential = "",
      color = "",
      url = ""
    )

    val savedUser = userManager.create(user).await.value
    val savedUpdatedUser =
      userManager.updateEmail(savedUser, "new-email").await.value

    assert(savedUpdatedUser.email == "new-email")
  }

  "updating or creating a user with an email already in the system" should "return an error" in {
    val user = createUser()

    val error =
      userManager.create(user.copy(nodeId = "")).await.left.value
    assert(error.isInstanceOf[PredicateError])

    val anotherUser = createUser(email = "test")
    val updateError = userManager.updateEmail(user, "test").await.left.value
    assert(updateError.isInstanceOf[PredicateError])
  }

  "new user invites" should "allow new users to be created with access to their invited org" in {

    val mockCognito = new MockCognito()

    val email = "inviteme@test.com"
    val firstName = "Fynn"
    val lastName = "Blackwell"

    val userInvite = userInviteManager
      .createOrRefreshUserInvite(
        organization = testOrganization,
        email = email,
        firstName = firstName,
        lastName = lastName,
        permission = DBPermission.Delete,
        ttl = Duration.ofSeconds(60)
      )(userManager, mockCognito, global)
      .await
      .value

    val user = userManager
      .createFromInvite(
        cognitoId = userInvite.cognitoId,
        firstName = "test",
        lastName = "tester",
        middleInitial = None,
        degree = None,
        title = "title"
      )(organizationManager(), userInviteManager, global)
      .await
      .value

    assert(user.email == email)
    assert(user.preferredOrganizationId == Some(testOrganization.id))

    val secureOrganizationManager = organizationManager(user)
    secureOrganizationManager.get(testOrganization.id).await.value

    assert(
      userManager.getOrganizations(user).await.value.contains(testOrganization)
    )

    assert(userManager.getByCognitoId(userInvite.cognitoId).await.value == user)
  }

  "new user invites" should "not be created if the email already belongs to the organization" in {}

  "a new user with multiple invites" should "properly be consumed" in {
    val mockCognito = new MockCognito()

    val email = "inviteme@test.com"
    val firstName = "Fynn"
    val lastName = "Blackwell"

    //create 5 org, 5 users 5 invites
    val orgsInvites: Seq[(Organization, UserInvite)] =
      (1 to 5).map { i =>
        val newOrg = createOrganization()
        val newDataset = createDataset(newOrg)
        val userInvite = userInviteManager
          .createOrRefreshUserInvite(
            organization = newOrg,
            email = email,
            firstName = firstName,
            lastName = lastName,
            permission = DBPermission.Delete,
            ttl = Duration.ofSeconds(600)
          )(userManager, mockCognito, global)
          .await
          .value

        assert(userInviteManager.isValid(userInvite))

        (newOrg, userInvite)
      }

    val invites = orgsInvites.map(_._2)
    assert(invites.forall(invite => userInviteManager.isValid(invite)))
    // All invites should be tied to the same Cognito ID
    assert(invites.forall(_.cognitoId == invites.head.cognitoId))

    // create 1 user from the first org and invite.
    // this will add the user to all the orgs she's invited to!

    val user = userManager
      .createFromInvite(
        cognitoId = orgsInvites.head._2.cognitoId,
        firstName = "test",
        lastName = "tester",
        title = "title",
        middleInitial = None,
        degree = None
      )(organizationManager(), userInviteManager, global)
      .await
      .value

    assert(user.email == email)
    assert(user.preferredOrganizationId == Some(orgsInvites.last._1.id))

    val secureOrganizationManager =
      new SecureOrganizationManager(database, user)
    orgsInvites.foreach {
      case (org, _) =>
        secureOrganizationManager.get(org.id).await.value
    }

    //get all the orgs the first user is a member of
    val allOrgIds = userManager.getOrganizations(user).await.value.map(_.nodeId)
    val createdOrgIds = orgsInvites.map(_._1.nodeId).toSet

    createdOrgIds.size should equal(5)

    allOrgIds should contain theSameElementsAs createdOrgIds
  }

  "getByCognitoId" should "get the correct user" in {

    val alice = createUser(email = "alice@pennsieve.net")
    val bob = createUser(email = "bob@pennsieve.net")
    val charlie = createUser(email = "charlie@pennsieve.net")

    userManager
      .getByCognitoId(alice.cognitoId.get)
      .await shouldBe Right(alice)
    userManager
      .getByCognitoId(bob.cognitoId.get)
      .await shouldBe Right(bob)
    userManager
      .getByCognitoId(charlie.cognitoId.get)
      .await shouldBe Right(charlie)
  }

  "creating a new user" should "select a random avatar color" in {
    val colors = List(
      "#342E37",
      "#F9CB40",
      "#FF715B",
      "#654597",
      "#F45D01",
      "#DF2935",
      "#00635D",
      "#4C212A",
      "#00635D",
      "#7765E3",
      "#B74F6F",
      "#EE8434",
      "#3B28CC",
      "#5FBFF9",
      "#474647"
    )

    val user1 = createUser()
    assert(colors.contains(user1.color))
  }

}
