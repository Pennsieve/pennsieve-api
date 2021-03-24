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

import com.pennsieve.models.DBPermission.Delete
import org.scalatest.EitherValues._
import org.scalatest.Matchers
import scala.concurrent.ExecutionContext.Implicits.global
import com.pennsieve.aws.cognito.MockCognito

class UserInviteManagerSpec extends BaseManagerSpec with Matchers {

  "createUserInvite" should "only allow one email invite per organization" in {

    val mockCognito = new MockCognito()

    val email = "inviteme@test.com"
    val firstName = "Fynn"
    val lastName = "Blackwell"

    val firstInvite = userInviteManager
      .createOrRefreshUserInvite(
        organization = testOrganization,
        email = email,
        firstName = firstName,
        lastName = lastName,
        permission = Delete,
        ttl = Duration.ofSeconds(60)
      )(userManager, mockCognito, global)
      .await
      .right
      .value

    val secondInvite = userInviteManager
      .createOrRefreshUserInvite(
        organization = testOrganization,
        email = email,
        firstName = firstName,
        lastName = lastName,
        permission = Delete,
        ttl = Duration.ofSeconds(60)
      )(userManager, mockCognito, global)
      .await
      .right
      .value

    userInviteManager
      .getByEmail(secondInvite.email)
      .await
      .right
      .value should have size 1
  }

  "refreshUserInviteToken" should "refresh the invite in Cognito" in {

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
        permission = Delete,
        ttl = Duration.ofSeconds(60)
      )(userManager, mockCognito, global)
      .await
      .right
      .value

    val refreshedUserInvite = userInviteManager
      .createOrRefreshUserInvite(
        organization = testOrganization,
        email = email,
        firstName = firstName,
        lastName = lastName,
        permission = Delete,
        ttl = Duration.ofSeconds(60)
      )(userManager, mockCognito, global)
      .await
      .right
      .value

    mockCognito.reSentInvites.get(email) shouldBe Some(userInvite.cognitoId)
  }
}
