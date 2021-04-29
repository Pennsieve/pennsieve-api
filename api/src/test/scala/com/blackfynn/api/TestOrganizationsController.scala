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

import cats.data._
import cats.implicits._
import com.pennsieve.aws.email.Email
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.db.UserInvitesMapper
import com.pennsieve.dtos.{ DatasetStatusDTO, TeamDTO, UserDTO, UserInviteDTO }
import com.pennsieve.managers.{
  DatasetStatusManager,
  SecureOrganizationManager,
  UpdateOrganization
}
import com.pennsieve.models.DBPermission.{ Administer, Delete, Owner }
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.Collection
import com.pennsieve.models.SubscriptionStatus.{
  ConfirmedSubscription,
  PendingSubscription
}
import com.pennsieve.models._
import com.pennsieve.models.DateVersion._
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.traits.PostgresProfile.api._
import java.time.{ Duration, ZonedDateTime }

import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.clients.{
  CustomTermsOfServiceClient,
  MockCustomTermsOfServiceClient
}
import com.pennsieve.helpers.{ DataSetTestMixin, MockAuditLogger }
import com.pennsieve.managers.OrganizationManager.Invite
import org.apache.http.impl.client.HttpClients
import org.json4s.jackson.Serialization.{ read, write }
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

class TestOrganizationsController extends BaseApiTest with DataSetTestMixin {

  val mockCustomTermsOfServiceClient: MockCustomTermsOfServiceClient =
    new MockCustomTermsOfServiceClient

  val mockAuditLogger: Auditor = new MockAuditLogger()
  val mockCognito: MockCognito = new MockCognito()

  override def afterStart(): Unit = {
    super.afterStart()
    addServlet(
      new OrganizationsController(
        insecureContainer,
        secureContainerBuilder,
        mockAuditLogger,
        mockCustomTermsOfServiceClient,
        mockCognito,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockCustomTermsOfServiceClient.reset
    mockCognito.reset
  }

  def makeUser(email: String): User =
    User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "firsty",
      middleInitial = Some("I"),
      lastName = "lasty",
      degree = None,
      credential = "cred",
      color = "color",
      url = "https://user.com"
    )

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

  test("get organizations") {
    get(s"", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include(loggedInOrganization.nodeId)
      body should include("\"isAdmin\":true")
    }
  }

  test("get an organization for non admin user") {
    get(
      s"/${loggedInOrganization.nodeId}",
      headers = authorizationHeader(colleagueJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInOrganization.name)
      body should include("\"isAdmin\":false")
    }
  }

  test("get an organization") {
    get(
      s"/${loggedInOrganization.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInOrganization.name)
      body should include("\"isAdmin\":true")
    }
  }

  test("get an organization with a JWT") {
    get(
      s"/${loggedInOrganization.nodeId}",
      headers = jwtUserAuthorizationHeader(loggedInOrganization) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInOrganization.name)
    }
  }

  test("update an organization name") {
    get(
      s"/${loggedInOrganization.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInOrganization.name)

      val createReq =
        write(UpdateOrganization(name = Some("Boom"), subscription = None))

      putJson(
        s"/${loggedInOrganization.nodeId}",
        createReq,
        headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
      ) {
        status should equal(200)
        body should include("Boom")
      }
    }
  }

  test("demo user should not be able to update the demo organization") {
    val createReq =
      write(UpdateOrganization(name = Some("Boom"), subscription = None))

    putJson(
      s"/${sandboxOrganization.nodeId}",
      createReq,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("update an organizations subscription requires Owner permission") {
    val updateReq = write(
      UpdateOrganization(
        name = Some("Boom"),
        subscription = Some(
          Subscription(
            organizationId = loggedInOrganization.id,
            status = ConfirmedSubscription,
            `type` = None,
            acceptedBy = Some("Joe User"),
            acceptedForOrganization = Some("My Org")
          )
        )
      )
    )

    putJson(
      s"/${loggedInOrganization.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }

    organizationManager
      .updateUserPermission(loggedInOrganization, loggedInUser, Owner)
      .await

    putJson(
      s"/${loggedInOrganization.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("date")
    }
  }

  test("update an organization with a Trial subscription") {
    organizationManager
      .updateUserPermission(loggedInOrganization, loggedInUser, Owner)
      .await

    val currentSubscription = organizationManager
      .getSubscription(loggedInOrganization.id)
      .await
      .right
      .value

    assert(currentSubscription.`type`.isEmpty)

    val newSubscription = currentSubscription.copy(`type` = Some("Trial"))

    val updateReq =
      write(
        UpdateOrganization(name = None, subscription = Some(newSubscription))
      )

    putJson(
      s"/${loggedInOrganization.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(
        organizationManager
          .getSubscription(loggedInOrganization.id)
          .await
          .right
          .value
          .`type`
          .contains("Trial")
      )
    }
  }

  test("an empty organization should have a publishers team") {
    get(
      s"/${loggedInOrganization.nodeId}/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val response = parsedBody.extract[List[ExpandedTeamResponse]]
      response.head.team.systemTeamType shouldEqual Some(
        SystemTeamType.Publishers
      )
      response.head.team.name shouldEqual SystemTeamType.Publishers.entryName.capitalize
    }
  }

  test("get an organization's teams") {
    teamManager.create("Boom", loggedInOrganization).await

    get(
      s"/${loggedInOrganization.nodeId}/teams",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("get an organization's team") {
    val team =
      teamManager.create("Boom", loggedInOrganization).await.right.value

    get(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("create an organization team") {
    val createReq = write(CreateGroupRequest("Boom"))

    postJson(
      s"/${loggedInOrganization.nodeId}/teams",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      body should include("Boom")
      body should not include (loggedInUser.nodeId)
    }
  }

  test("create an duplicate organization team") {

    val team1 =
      teamManager.create("Boom", loggedInOrganization).await.right.value

    val createReq = write(CreateGroupRequest("Boom"))

    postJson(
      s"/${loggedInOrganization.nodeId}/teams",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      body should include("team names must be unique")
    }
  }

  test("update an organization team") {
    val team1 =
      teamManager.create("Foo", loggedInOrganization).await.right.value
    val team2 =
      teamManager.create("Bar", loggedInOrganization).await.right.value

    val createReq = write(UpdateGroupRequest("Boom"))

    putJson(
      s"/${loggedInOrganization.nodeId}/teams/${team2.nodeId}",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("update an organization team with a duplicate") {
    val team1 =
      teamManager.create("Foo", loggedInOrganization).await.right.value
    val team2 =
      teamManager.create("Bar", loggedInOrganization).await.right.value

    val createReq = write(UpdateGroupRequest("Bar"))

    putJson(
      s"/${loggedInOrganization.nodeId}/teams/${team1.nodeId}",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      body should include("name must be unique")
    }
  }

  test("delete an organization team") {
    val team = teamManager.create("Foo", loggedInOrganization).await.right.value

    assert(
      organizationManager
        .getTeams(loggedInOrganization)
        .await
        .right
        .value
        .size == 2
    )
    delete(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(
        organizationManager
          .getTeams(loggedInOrganization)
          .await
          .right
          .value
          .size == 1
      )
    }
  }

  test("cannot delete a system team") {
    val publisherTeam =
      organizationManager.getPublisherTeam(loggedInOrganization).await.value

    delete(
      s"/${loggedInOrganization.nodeId}/teams/${publisherTeam._1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      assert(
        organizationManager
          .getTeams(loggedInOrganization)
          .await
          .right
          .value
          .size == 1
      )
    }
  }

  test("get an organization's members") {
    get(
      s"/${loggedInOrganization.nodeId}/members",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include(loggedInUser.nodeId)
    }
  }

  test("add an organization member") {
    // not existing user
    val email = "another@test.com"
    val inviteRequest = Invite(email, "Fynn", "Blackwell")
    val createReq = write(AddToOrganizationRequest(Set(inviteRequest)))

    postJson(
      s"/${loggedInOrganization.nodeId}/members",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody.extract[Map[String, AddUserResponse]]
      results.get(email).value.success should be(true)
    }

    mockCognito.sentInvites.toList.map(_.address) shouldBe List(email)

    // existing user
    val createReq2 = write(
      AddToOrganizationRequest(
        Set(
          Invite(
            externalUser.email,
            externalUser.firstName,
            externalUser.lastName
          )
        )
      )
    )

    postJson(
      s"/${loggedInOrganization.nodeId}/members",
      createReq2,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val results = parsedBody.extract[Map[String, AddUserResponse]]
      results.get(externalUser.email).value.success should be(true)
    }

    // Should not send another request to Cognito
    mockCognito.sentInvites.toList.map(_.address) shouldBe List(email)
  }

  test("remove an organization member") {

    val user = userManager
      .create(makeUser("remove@test.com"))
      .await
      .right
      .value

    organizationManager.addUser(loggedInOrganization, user, Delete).await

    val newDataset = secureDataSetManager
      .create("Test", Some("Test Dataset"))
      .await
      .value

    secureDataSetManager.addCollaborators(newDataset, Set(user.nodeId)).await
    assert(
      secureDataSetManager.find(user, Role.Viewer).await.right.value.size == 1
    )
    delete(
      s"/${loggedInOrganization.nodeId}/members/${user.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      body should equal("")
      status should equal(200)

      organizationManager
        .getUsers(loggedInOrganization)
        .await
        .right
        .value should not contain user

      assert(
        secureDataSetManager.find(user, Role.Viewer).await.right.value.isEmpty
      )
    }
  }

  test("update an organization's member") {
    val createReq =
      write(UpdateMemberRequest(Some("Boom"), None, None, None, None, None))

    putJson(
      s"/${loggedInOrganization.nodeId}/members/${loggedInUser.nodeId}",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      body should include("Boom")
      status should equal(200)
    }
  }

  // update an organization's member's permission
  test("update an organization's member's permission") {
    val createReq =
      write(UpdateMemberRequest(None, None, None, None, None, Some(Administer)))

    putJson(
      s"/${loggedInOrganization.nodeId}/members/${colleagueUser.nodeId}",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      // make sure user has permission
      new SecureOrganizationManager(secureContainer.db, colleagueUser)
        .hasPermission(loggedInOrganization, Administer)
        .await
        .value
      status should equal(200)
    }
  }

  test("add an organization team member") {
    val team = teamManager.create("Foo", loggedInOrganization).await.right.value

    val member =
      userManager
        .create(makeUser("another@test.com"))
        .await
        .right
        .value

    val createReq = write(AddToTeamRequest(List(member.nodeId)))

    postJson(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}/members",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("another@test.com")
    }
  }

  test("remove an organization team member") {
    val team = teamManager.create("Foo", loggedInOrganization).await.right.value

    val user =
      userManager
        .create(makeUser("another@test.com"))
        .await
        .right
        .value
    organizationManager.addUser(loggedInOrganization, user, Delete).await

    teamManager.addUser(team, user, Delete).await

    assert(teamManager.getUsers(team).await.right.value.size == 1)
    delete(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}/members/${user.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(teamManager.getUsers(team).await.right.value.size == 0)
    }
  }

  // get invite
  test("get all invites for a organization") {
    val invites: List[UserInvite] = (1 to 5).toList.map { i =>
      userInviteManager
        .createOrRefreshUserInvite(
          loggedInOrganization,
          s"email$i",
          s"first$i",
          s"last$i",
          DBPermission.Delete,
          Duration.ofSeconds(60)
        )(userManager, mockCognito, ec)
        .await
        .right
        .value
    }

    get(
      s"/${loggedInOrganization.nodeId}/invites",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      val reqInvites = parsedBody.extract[List[UserInviteDTO]]

      reqInvites.map(_.id).toSet should equal(invites.map(_.nodeId).toSet)
    }
  }

  // put invite
  test("refresh an expired invite in Cognito") {
    val email = "new+member@test.com"
    val invalidInvite = userInviteManager
      .createOrRefreshUserInvite(
        loggedInOrganization,
        email,
        "first",
        "last",
        DBPermission.Delete,
        Duration.ofSeconds(-1)
      )(userManager, mockCognito, ec)
      .await
      .right
      .value

    put(
      s"/${loggedInOrganization.nodeId}/invites/${invalidInvite.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      val response = parsedBody.extract[AddUserResponse]

      mockCognito.reSentInvites.get(Email(email)) shouldBe Some(
        invalidInvite.cognitoId
      )
    }
  }

  test("put invite where email is already a user") {
    val email = "inviteTester@test.com"
    val firstName = "Fynn"
    val lastName = "Blackwell"
    val invalidInvite = userInviteManager
      .createOrRefreshUserInvite(
        loggedInOrganization,
        email,
        firstName,
        lastName,
        DBPermission.Delete,
        Duration.ofSeconds(-1)
      )(userManager, mockCognito, ec)
      .await
      .right
      .value

    val invitedUser = userManager
      .create(makeUser(email))
      .await
      .right
      .value

    userInviteManager.isValid(invalidInvite) should be(false)

    put(
      s"/${loggedInOrganization.nodeId}/invites/${invalidInvite.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      val response = parsedBody.extract[AddUserResponse]
      response.user.map(_.email) should be(Some("invitetester@test.com"))
      response.user.map(_.id) should be(Some(invitedUser.nodeId))

      userInviteManager.get(invalidInvite.id).await should be('Left)
    }
  }

  test("deleting an invite works") {
    val invite = userInviteManager
      .createOrRefreshUserInvite(
        loggedInOrganization,
        "inviteTest@test.com",
        "first",
        "last",
        DBPermission.Delete,
        Duration.ofSeconds(60)
      )(userManager, mockCognito, ec)
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/invites/${invite.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      userInviteManager.get(invite.id).await should be('Left)
    }
  }

  test("reassign package owner when a user is removed") {

    val user =
      userManager
        .create(makeUser("another@test.com"))
        .await
        .right
        .value
    organizationManager.addUser(loggedInOrganization, user, Delete).await

    val owner =
      userManager
        .create(makeUser("owner@test.com"))
        .await
        .right
        .value
    organizationManager.addUser(loggedInOrganization, owner, Owner).await
    val pkg = packageManager
      .create(
        "test package",
        Collection,
        READY,
        dataset,
        Some(user.id),
        None,
        None,
        List.empty
      )
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/members/${user.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      body should equal("")
      status should equal(200)
      assert(
        userManager.getPackages(user, loggedInOrganization).await.value.isEmpty
      )
      assert(
        userManager
          .getPackages(owner, loggedInOrganization)
          .await
          .value
          .size == 1
      )
      assert(
        packageManager.get(pkg.id).await.right.value.ownerId.contains(owner.id)
      )
    }
  }

  test("clear package owner when a user is removed and org owner is missing") {

    val user =
      userManager
        .create(makeUser("another@test.com"))
        .await
        .right
        .value
    organizationManager.addUser(loggedInOrganization, user, Delete).await
    val pkg = packageManager
      .create(
        "test package",
        Collection,
        READY,
        dataset,
        Some(user.id),
        None,
        None,
        List.empty
      )
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/members/${user.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      body should equal("")
      status should equal(200)
      assert(
        userManager.getPackages(user, loggedInOrganization).await.value.isEmpty
      )
      assert(packageManager.get(pkg.id).await.right.value.ownerId.isEmpty)
    }
  }

  test("get custom terms for a non-existent version fails") {
    get(
      s"/${loggedInOrganization.nodeId}/custom-terms-of-service",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404) // not found
    }
  }

  test("get custom terms succeeds") {
    val dv1 = DateVersion.from("19990909120000").right.get
    val dv2 = DateVersion.from("20080530053000").right.get
    mockCustomTermsOfServiceClient.updateTermsOfService(
      loggedInOrganization.nodeId,
      "VERSION-1",
      dv1
    )
    // Need to update the logged in org as well:
    organizationManager
      .update(
        loggedInOrganization
          .copy(customTermsOfServiceVersion = Some(dv1.toZonedDateTime))
      )
      .await
    get(
      s"/${loggedInOrganization.nodeId}/custom-terms-of-service",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should be("VERSION-1")
    }
    mockCustomTermsOfServiceClient.updateTermsOfService(
      loggedInOrganization.nodeId,
      "VERSION-2",
      dv2
    )
    organizationManager
      .update(
        loggedInOrganization
          .copy(customTermsOfServiceVersion = Some(dv2.toZonedDateTime))
      )
      .await
    get(
      s"/${loggedInOrganization.nodeId}/custom-terms-of-service",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should be("VERSION-2")
    }
  }

  test("new custom terms supersedes old") {
    val dv1 = DateVersion.from("19990909120000").right.get
    val dv2 = DateVersion.from("20080530053000").right.get
    mockCustomTermsOfServiceClient.updateTermsOfService(
      loggedInOrganization.nodeId,
      "VERSION-1",
      dv1
    )
    organizationManager
      .update(
        loggedInOrganization
          .copy(customTermsOfServiceVersion = Some(dv1.toZonedDateTime))
      )
      .await
    mockCustomTermsOfServiceClient.updateTermsOfService(
      loggedInOrganization.nodeId,
      "VERSION-2",
      dv2
    )
    organizationManager
      .update(
        loggedInOrganization
          .copy(customTermsOfServiceVersion = Some(dv2.toZonedDateTime))
      )
      .await
    get(
      s"/${loggedInOrganization.nodeId}/custom-terms-of-service",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should be("VERSION-2")
    }
  }

  test("get default dataset status options for an organization") {

    get(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody
        .extract[List[DatasetStatusDTO]]
        .map(s => (s.name, s.displayName, s.inUse)) shouldBe List(
        ("NO_STATUS", "No Status", DatasetStatusInUse(true)), // The base dataset is using this status
        ("WORK_IN_PROGRESS", "Work in Progress", DatasetStatusInUse(false)),
        ("IN_REVIEW", "In Review", DatasetStatusInUse(false)),
        ("COMPLETED", "Completed", DatasetStatusInUse(false))
      )
    }
  }

  test("create dataset status options for organization") {

    val createRequest =
      write(DatasetStatusRequest("Ready for Publication", "#71747C"))

    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      createRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DatasetStatusDTO]

      dto.name shouldBe "READY_FOR_PUBLICATION"
      dto.displayName shouldBe "Ready for Publication"
      dto.color shouldBe "#71747C"
      dto.inUse shouldBe DatasetStatusInUse(false)
    }

    secureContainer.datasetStatusManager.getAll.await.right.value
      .map(s => (s.name, s.displayName)) shouldBe List(
      ("NO_STATUS", "No Status"),
      ("WORK_IN_PROGRESS", "Work in Progress"),
      ("IN_REVIEW", "In Review"),
      ("COMPLETED", "Completed"),
      ("READY_FOR_PUBLICATION", "Ready for Publication")
    )
  }

  test(
    "demo users should not be able to create dataset options for the demo organization"
  ) {
    val createRequest =
      write(DatasetStatusRequest("Ready for Publication", "#71747C"))

    postJson(
      s"/${sandboxOrganization.nodeId}/dataset-status",
      createRequest,
      headers = authorizationHeader(sandboxUserJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("validate dataset status options color") {

    val request =
      write(DatasetStatusRequest("Ready for Publication", "#aaaaaa"))

    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    val datasetStatus =
      secureContainer.datasetStatusManager.getAll.await.right.value.head

    putJson(
      s"/${loggedInOrganization.nodeId}/dataset-status/${datasetStatus.id}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("validate dataset status options length") {
    val request =
      write(
        DatasetStatusRequest(
          "A very long status, truly a novel, words all across the screen",
          "#71747C"
        )
      )

    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    val datasetStatus =
      secureContainer.datasetStatusManager.getAll.await.right.value.head

    putJson(
      s"/${loggedInOrganization.nodeId}/dataset-status/${datasetStatus.id}",
      request,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("update dataset status options for organization") {

    val datasetStatus = secureContainer.datasetStatusManager
      .create("In Progress", color = "#71747C")
      .await
      .right
      .value

    val updateRequest =
      write(DatasetStatusRequest("Ready for Publication", "#2760FF"))

    putJson(
      s"/${loggedInOrganization.nodeId}/dataset-status/${datasetStatus.id}",
      updateRequest,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DatasetStatusDTO]

      dto.name shouldBe "READY_FOR_PUBLICATION"
      dto.displayName shouldBe "Ready for Publication"
      dto.color shouldBe "#2760FF"
    }

    secureContainer.datasetStatusManager
      .get(datasetStatus.id)
      .map(s => (s.name, s.displayName, s.color))
      .await
      .right
      .value shouldBe ("READY_FOR_PUBLICATION", "Ready for Publication", "#2760FF")
  }

  test("delete dataset status options for organization") {

    val status1 = secureContainer.datasetStatusManager
      .create(displayName = "Ready for Publication", color = "#71747C")
      .await
      .right
      .value

    val dataset1 = secureContainer.datasetManager
      .create(
        name = "My dataset",
        description = None,
        statusId = Some(status1.id)
      )
      .await
      .right
      .value

    val status2 =
      secureContainer.datasetStatusManager.getAll.await.right.value(2)

    val dataset2 = secureContainer.datasetManager
      .create(
        name = "Another dataset",
        description = None,
        statusId = Some(status2.id)
      )
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/dataset-status/${status1.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      parsedBody.extract[DatasetStatusDTO].name shouldBe "READY_FOR_PUBLICATION"
    }

    secureContainer.datasetStatusManager.getAll.await.right.value
      .map(s => (s.name, s.displayName)) shouldBe List(
      ("NO_STATUS", "No Status"),
      ("WORK_IN_PROGRESS", "Work in Progress"),
      ("IN_REVIEW", "In Review"),
      ("COMPLETED", "Completed")
    )

    // dataset1's status should be replaced with the default status.
    secureContainer.datasetManager
      .get(dataset1.id)
      .await
      .right
      .value
      .statusId shouldBe defaultDatasetStatus.id

    // However, dataset2's status should be untouched.
    secureContainer.datasetManager
      .get(dataset2.id)
      .await
      .right
      .value
      .statusId shouldBe status2.id
  }

  test("cannot delete all dataset status options") {

    val datasetStatus =
      secureContainer.datasetStatusManager.getAll.await.right.value.head

    // Remove all but one status
    secureContainer.db
      .run(
        secureContainer.datasetStatusManager.datasetStatusMapper
          .filter(_.id =!= datasetStatus.id)
          .delete
      )
      .await
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/dataset-status/${datasetStatus.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test(
    "dataset status options are scoped to organization in URL, not secureContainer"
  ) {
    // Even if a request is made with a JWT for another organization, if the
    // user has sufficient permissions on the organization in the URL then the
    // operation should be performed on the organization in the URL.
    //
    // For this test, `externalUser` is part of `loggedInOrganization`. Their
    // `externalJWT` should still be able perform actions on the
    // `loggedInOrganization` passed in the URI, not the organization in the
    // JWT.
    //
    // Note: the root cause of this is probably a bug in authentication service:
    // if a URL has an organization ID, we should probably use it when
    // constructing the JWT.

    organizationManager
      .addUser(loggedInOrganization, externalUser, Administer)
      .await
      .value

    val datasetStatusManager = secureContainer.datasetStatusManager
    val externalDatasetStatusManager =
      new DatasetStatusManager(secureContainer.db, externalOrganization)

    // Can create status in `loggedInOrganization`
    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      write(DatasetStatusRequest("Ready for Publication", "#71747C")),
      headers = authorizationHeader(externalJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val statusId = parsedBody.extract[DatasetStatusDTO].id

      datasetStatusManager.getAll.await.right.value
        .map(_.name) should contain("READY_FOR_PUBLICATION")
      externalDatasetStatusManager.getAll.await.right.value
        .map(_.name) should not contain ("READY_FOR_PUBLICATION")

      // Can get status in `loggedInOrganization`
      get(
        s"/${loggedInOrganization.nodeId}/dataset-status",
        headers = authorizationHeader(externalJwt) ++ traceIdHeader()
      ) {
        status should equal(200)
        parsedBody
          .extract[List[DatasetStatusDTO]]
          .map(_.id) should contain(statusId)
      }

      // Can update status in `loggedInOrganization`
      putJson(
        s"/${loggedInOrganization.nodeId}/dataset-status/$statusId",
        write(DatasetStatusRequest("Not Quite Ready", "#71747C")),
        headers = authorizationHeader(externalJwt) ++ traceIdHeader()
      ) {
        status should equal(200)
      }

      // Can delete status in `loggedInOrganization`
      delete(
        s"/${loggedInOrganization.nodeId}/dataset-status/$statusId",
        headers = authorizationHeader(externalJwt) ++ traceIdHeader()
      ) {
        status should equal(200)
      }
    }
  }

  test("create data use agreements for organization") {

    postJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      write(
        CreateDataUseAgreementRequest(
          name = "New data use agreement",
          body = "Lots of legal text",
          description = Some("Description")
        )
      ),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DataUseAgreementDTO]
      dto.name shouldBe "New data use agreement"
      dto.body shouldBe "Lots of legal text"
      dto.description shouldBe "Description"
    }
  }

  test("names of data use agreement must be unique") {

    postJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      write(
        CreateDataUseAgreementRequest(
          name = "New data use agreement",
          body = "Lots of legal text"
        )
      ),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    postJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      write(
        CreateDataUseAgreementRequest(
          name = "New data use agreement",
          body = "Lots of legal text"
        )
      ),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("demo user cannot create data use agreements for organization") {

    postJson(
      s"/${sandboxOrganization.nodeId}/data-use-agreements",
      write(
        CreateDataUseAgreementRequest(
          name = "New data use agreement",
          body = "Lots of legal text",
          description = Some("Description")
        )
      ),
      headers = authorizationHeader(sandboxUserJwt)
    ) {
      status should equal(403)
    }
  }

  test("get data use agreements") {

    val agreement1 = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text")
      .await
      .right
      .value

    val agreement2 = secureContainer.dataUseAgreementManager
      .create("Another data use agreement", "Lots of legal text")
      .await
      .right
      .value

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[DataUseAgreementDTO]].map(_.id) shouldBe List(
        agreement1.id,
        agreement2.id
      )
    }
  }

  test("delete unused data use agreement") {

    val agreement = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text")
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${agreement.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[DataUseAgreementDTO]] shouldBe empty
    }
  }

  test("update data use agreement") {

    val agreement = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text")
      .await
      .right
      .value

    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${agreement.id}",
      write(
        UpdateDataUseAgreementRequest(
          name = Some("New name"),
          description = Some("Description")
        )
      ),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody
        .extract[List[DataUseAgreementDTO]]
        .map(a => (a.name, a.description)) shouldBe List(
        ("New name", "Description")
      )
    }
  }

  test("fail to update non-existent data use agreement") {

    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/300",
      write(UpdateDataUseAgreementRequest(name = Some("New name"))),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
    }
  }

  test("change default data use agreement") {

    val agreement1 = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text", isDefault = true)
      .await
      .right
      .value

    val agreement2 = secureContainer.dataUseAgreementManager
      .create(
        "Another data use agreement",
        "Lots of legal text",
        isDefault = false
      )
      .await
      .right
      .value

    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${agreement2.id}",
      write(UpdateDataUseAgreementRequest(isDefault = Some(true))),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody
        .extract[List[DataUseAgreementDTO]]
        .map(a => (a.name, a.isDefault)) shouldBe List(
        ("Another data use agreement", true),
        ("New data use agreement", false)
      )
    }
  }

  test("cannot delete data use agreement that is used by a dataset") {

    val agreement = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text")
      .await
      .right
      .value

    val dataset =
      createDataSet("Dataset", dataUseAgreement = Some(agreement))

    delete(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${agreement.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[DataUseAgreementDTO]] shouldBe List(
        DataUseAgreementDTO(agreement)
      )
    }
  }

  test(
    "cannot delete data use agreement that has been signed, even if not used for dataset"
  ) {

    val agreement = secureContainer.dataUseAgreementManager
      .create("New data use agreement", "Lots of legal text")
      .await
      .right
      .value

    val dataset =
      createDataSet("Dataset", dataUseAgreement = Some(agreement))

    secureContainer.datasetPreviewManager
      .requestAccess(dataset, colleagueUser, Some(agreement))
      .await
      .right
      .value

    // Remove agreement from dataset, only linked via previewer
    secureContainer.datasetManager
      .update(dataset.copy(dataUseAgreementId = None))
      .await
      .right
      .value

    delete(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${agreement.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }

    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[DataUseAgreementDTO]] shouldBe List(
        DataUseAgreementDTO(agreement)
      )
    }
  }
}
