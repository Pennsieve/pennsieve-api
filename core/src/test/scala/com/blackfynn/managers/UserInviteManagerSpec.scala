package com.blackfynn.managers

import java.time.Duration

import com.blackfynn.models.DBPermission.Delete
import org.scalatest.EitherValues._
import org.scalatest.Matchers
import scala.concurrent.ExecutionContext.Implicits.global
import com.blackfynn.aws.cognito.MockCognito

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
