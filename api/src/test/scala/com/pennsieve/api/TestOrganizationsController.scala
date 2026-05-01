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

import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.clients.MockCustomTermsOfServiceClient
import com.pennsieve.dtos.DatasetStatusDTO
import com.pennsieve.helpers.{ APIContainers, MockAuditLogger }
import com.pennsieve.managers.UpdateOrganization
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  DatasetStatusInUse,
  Degree,
  Feature,
  NodeCodes,
  Organization,
  OrganizationUser,
  Subscription,
  SubscriptionStatus,
  SystemTeamType,
  User
}
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.time.Duration
import java.util.UUID

class TestOrganizationsController extends BaseApiUnitTest {

  val mockCustomTermsOfServiceClient: MockCustomTermsOfServiceClient =
    new MockCustomTermsOfServiceClient
  val mockAuditLogger: Auditor = new MockAuditLogger()
  override lazy val mockCognito: MockCognito = new MockCognito()

  var loggedInUser: User = _
  var colleagueUser: User = _
  var externalUser: User = _
  var sandboxUser: User = _
  var guestUser: User = _
  var integrationUser: User = _
  var loggedInOrganization: Organization = _
  var sandboxOrganization: Organization = _
  var externalOrganization: Organization = _
  var loggedInJwt: String = _
  var colleagueJwt: String = _
  var externalJwt: String = _
  var sandboxUserJwt: String = _
  var guestJwt: String = _
  var secureContainer: APIContainers.SecureAPIContainer = _

  override def beforeAll(): Unit = {
    super.beforeAll()
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
    state.clear()
    mockCustomTermsOfServiceClient.reset()
    mockCognito.reset()
    setUpFixtures()
  }

  private def setUpFixtures(): Unit = {
    loggedInOrganization = mkOrg("Test Org", "test-org")
    sandboxOrganization = mkOrg("Sandbox", "__sandbox__")
    state.featureFlags
      .put((sandboxOrganization.id, Feature.SandboxOrgFeature), true)
    externalOrganization = mkOrg("External Org", "external-org")

    loggedInUser = mkUser("test@test.com")
    colleagueUser = mkUser("colleague@test.com")
    externalUser = mkUser("external@test.com")
    sandboxUser = mkUser("sandbox@test.com")
    guestUser = mkUser("guest@test.com")
    integrationUser = mkUser("", isIntegrationUser = true)

    addOrgMember(loggedInOrganization, loggedInUser, DBPermission.Administer)
    addOrgMember(loggedInOrganization, colleagueUser, DBPermission.Delete)
    addOrgMember(loggedInOrganization, integrationUser, DBPermission.Delete)
    addOrgMember(loggedInOrganization, guestUser, DBPermission.Guest)
    addOrgMember(externalOrganization, externalUser, DBPermission.Delete)
    addOrgMember(sandboxOrganization, sandboxUser, DBPermission.Administer)
    addOrgMember(sandboxOrganization, loggedInUser, DBPermission.Administer)

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    colleagueJwt = mintUserJwt(colleagueUser, loggedInOrganization)
    externalJwt = mintUserJwt(externalUser, externalOrganization)
    sandboxUserJwt = mintUserJwt(sandboxUser, sandboxOrganization)
    guestJwt = mintUserJwt(guestUser, loggedInOrganization)

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)
    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value
  }

  private def mkOrg(name: String, slug: String): Organization = {
    val id = state.newId()
    val org = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = name,
      slug = slug,
      id = id
    )
    state.organizations.put(id, org)
    org
  }

  private def mkUser(
    email: String,
    isSuperAdmin: Boolean = false,
    isIntegrationUser: Boolean = false
  ): User = {
    val id = state.newId()
    val u = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "first",
      middleInitial = Some("M"),
      lastName = "last",
      degree = Some(Degree.MS),
      credential = "cred",
      color = "",
      url = "http://test.com",
      authyId = 0,
      isSuperAdmin = isSuperAdmin,
      isIntegrationUser = isIntegrationUser,
      preferredOrganizationId = None,
      status = true,
      orcidAuthorization = None,
      cognitoId =
        if (isIntegrationUser) None
        else Some(CognitoId.UserPoolId(UUID.randomUUID())),
      id = id
    )
    state.users.put(id, u)
    u
  }

  private def addOrgMember(
    org: Organization,
    user: User,
    permission: DBPermission
  ): Unit = {
    state.orgUserPermissions.put((org.id, user.id), permission)
    state.orgUsers
      .put((org.id, user.id), OrganizationUser(org.id, user.id, permission))
  }

  private def teamManager: com.pennsieve.managers.TeamManager =
    secureContainer.teamManager
  private def userManager: com.pennsieve.managers.UserManager =
    insecureContainer.userManager
  private def userInviteManager: com.pennsieve.managers.UserInviteManager =
    insecureContainer.userInviteManager

  // ---------------- Tests --------------------------------------------------

  test("swagger")(pending)

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

  test("update an organization name") {
    val req = write(
      UpdateOrganization(
        name = Some("Boom"),
        subscription = None,
        colorTheme = None
      )
    )
    putJson(
      s"/${loggedInOrganization.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("set an organization color theme") {
    val req = write(
      UpdateOrganization(
        name = None,
        subscription = None,
        colorTheme = Some(("ABC", "EFG"))
      )
    )
    putJson(
      s"/${loggedInOrganization.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("ABC")
      body should include("EFG")
    }
  }

  test("update an organizations subscription requires Owner permission") {
    val updateReq = write(
      UpdateOrganization(
        name = Some("Boom"),
        colorTheme = None,
        subscription = Some(
          Subscription(
            organizationId = loggedInOrganization.id,
            status = SubscriptionStatus.ConfirmedSubscription,
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

    state.orgUserPermissions
      .put((loggedInOrganization.id, loggedInUser.id), DBPermission.Owner)

    putJson(
      s"/${loggedInOrganization.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("update an organization with a Trial subscription") {
    state.orgUserPermissions
      .put((loggedInOrganization.id, loggedInUser.id), DBPermission.Owner)

    val current = secureContainer.organizationManager
      .getSubscription(loggedInOrganization.id)
      .await
      .value
    assert(current.`type`.isEmpty)

    val updateReq = write(
      UpdateOrganization(
        name = None,
        subscription = Some(current.copy(`type` = Some("Trial"))),
        colorTheme = None
      )
    )

    putJson(
      s"/${loggedInOrganization.nodeId}",
      updateReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(
        secureContainer.organizationManager
          .getSubscription(loggedInOrganization.id)
          .await
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
    val team = teamManager.create("Boom", loggedInOrganization).await.value
    get(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("create an organization team") {
    val req = write(CreateGroupRequest("Boom"))
    postJson(
      s"/${loggedInOrganization.nodeId}/teams",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      body should include("Boom")
      body should not include (loggedInUser.nodeId)
    }
  }

  test("create an duplicate organization team") {
    teamManager.create("Boom", loggedInOrganization).await.value
    val req = write(CreateGroupRequest("Boom"))
    postJson(
      s"/${loggedInOrganization.nodeId}/teams",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      body should include("team names must be unique")
    }
  }

  test("update an organization team") {
    teamManager.create("Foo", loggedInOrganization).await.value
    val team2 = teamManager.create("Bar", loggedInOrganization).await.value
    val req = write(UpdateGroupRequest("Boom"))
    putJson(
      s"/${loggedInOrganization.nodeId}/teams/${team2.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("Boom")
    }
  }

  test("update an organization team with a duplicate") {
    val team1 = teamManager.create("Foo", loggedInOrganization).await.value
    teamManager.create("Bar", loggedInOrganization).await.value
    val req = write(UpdateGroupRequest("Bar"))
    putJson(
      s"/${loggedInOrganization.nodeId}/teams/${team1.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
      body should include("name must be unique")
    }
  }

  test("delete an organization team") {
    val team = teamManager.create("Foo", loggedInOrganization).await.value
    assert(
      secureContainer.organizationManager
        .getTeams(loggedInOrganization)
        .await
        .value
        .size == 2
    )
    delete(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(
        secureContainer.organizationManager
          .getTeams(loggedInOrganization)
          .await
          .value
          .size == 1
      )
    }
  }

  test("cannot delete a system team") {
    val publisherTeam = secureContainer.organizationManager
      .getPublisherTeam(loggedInOrganization)
      .await
      .value
    delete(
      s"/${loggedInOrganization.nodeId}/teams/${publisherTeam._1.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
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

  test("update an organization's member") {
    val req =
      write(UpdateMemberRequest(Some("Boom"), None, None, None, None, None))
    putJson(
      s"/${loggedInOrganization.nodeId}/members/${loggedInUser.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      body should include("Boom")
      status should equal(200)
    }
  }

  test("update an organization's member's permission") {
    val req = write(
      UpdateMemberRequest(
        None,
        None,
        None,
        None,
        None,
        Some(DBPermission.Administer)
      )
    )
    putJson(
      s"/${loggedInOrganization.nodeId}/members/${colleagueUser.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }
  }

  test("Cannot update an organization's integration member's permission") {
    val req = write(
      UpdateMemberRequest(
        None,
        None,
        None,
        None,
        None,
        Some(DBPermission.Administer)
      )
    )
    putJson(
      s"/${loggedInOrganization.nodeId}/members/${integrationUser.nodeId}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  test("add an organization team member") {
    val team = teamManager.create("Foo", loggedInOrganization).await.value
    val member = userManager
      .create(
        com.pennsieve.models.User(
          NodeCodes.generateId(NodeCodes.userCode),
          "another@test.com",
          "f",
          None,
          "l",
          None,
          "cred"
        )
      )
      .await
      .value
    secureContainer.organizationManager
      .addUser(loggedInOrganization, member, DBPermission.Delete)
      .await
      .value
    val req = write(AddToTeamRequest(List(member.nodeId)))
    postJson(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}/members",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("another@test.com")
    }
  }

  test("remove an organization team member") {
    val team = teamManager.create("Foo", loggedInOrganization).await.value
    val u = userManager
      .create(
        com.pennsieve.models.User(
          NodeCodes.generateId(NodeCodes.userCode),
          "another2@test.com",
          "f",
          None,
          "l",
          None,
          "cred"
        )
      )
      .await
      .value
    secureContainer.organizationManager
      .addUser(loggedInOrganization, u, DBPermission.Delete)
      .await
      .value
    teamManager.addUser(team, u, DBPermission.Delete).await
    assert(teamManager.getUsers(team).await.value.size == 1)
    delete(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}/members/${u.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(teamManager.getUsers(team).await.value.size == 0)
    }
  }

  test("get all invites for a organization") {
    val invites = (1 to 5).toList.map { i =>
      userInviteManager
        .createOrRefreshUserInvite(
          loggedInOrganization,
          s"invite-email$i@test.com",
          s"first$i",
          s"last$i",
          DBPermission.Delete,
          Duration.ofSeconds(60)
        )(userManager, mockCognito, ec)
        .await
        .value
    }
    get(
      s"/${loggedInOrganization.nodeId}/invites",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      val req = parsedBody.extract[List[com.pennsieve.dtos.UserInviteDTO]]
      req.map(_.id).toSet should equal(invites.map(_.nodeId).toSet)
    }
  }

  test("deleting an invite works") {
    val invite = userInviteManager
      .createOrRefreshUserInvite(
        loggedInOrganization,
        "invite-test@test.com",
        "first",
        "last",
        DBPermission.Delete,
        Duration.ofSeconds(60)
      )(userManager, mockCognito, ec)
      .await
      .value
    delete(
      s"/${loggedInOrganization.nodeId}/invites/${invite.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should be(200)
      userInviteManager.get(invite.id).await should be(Symbol("Left"))
    }
  }

  test("get custom terms for a non-existent version fails") {
    get(
      s"/${loggedInOrganization.nodeId}/custom-terms-of-service",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
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
        ("NO_STATUS", "No Status", DatasetStatusInUse(false)),
        ("WORK_IN_PROGRESS", "Work in Progress", DatasetStatusInUse(false)),
        ("IN_REVIEW", "In Review", DatasetStatusInUse(false)),
        ("COMPLETED", "Completed", DatasetStatusInUse(false))
      )
    }
  }

  test("create dataset status options for organization") {
    val req = write(DatasetStatusRequest("Ready for Publication", "#71747C"))
    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DatasetStatusDTO]
      dto.name shouldBe "READY_FOR_PUBLICATION"
      dto.displayName shouldBe "Ready for Publication"
      dto.color shouldBe "#71747C"
    }
  }

  test("validate dataset status options color") {
    val req = write(DatasetStatusRequest("Ready for Publication", "#aaaaaa"))
    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    val ds = secureContainer.datasetStatusManager.getAll.await.value.head
    putJson(
      s"/${loggedInOrganization.nodeId}/dataset-status/${ds.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("validate dataset status options length") {
    val req = write(
      DatasetStatusRequest(
        "A very long status, truly a novel, words all across the screen",
        "#71747C"
      )
    )
    postJson(
      s"/${loggedInOrganization.nodeId}/dataset-status",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }
  }

  test("update dataset status options for organization") {
    val ds = secureContainer.datasetStatusManager
      .create("In Progress", color = "#71747C")
      .await
      .value
    val req = write(DatasetStatusRequest("Ready for Publication", "#2760FF"))
    putJson(
      s"/${loggedInOrganization.nodeId}/dataset-status/${ds.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val dto = parsedBody.extract[DatasetStatusDTO]
      dto.name shouldBe "READY_FOR_PUBLICATION"
      dto.displayName shouldBe "Ready for Publication"
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
          name = "DUA1",
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
        CreateDataUseAgreementRequest(name = "DUA1", body = "Lots of text")
      ),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("get data use agreements") {
    val a1 = secureContainer.dataUseAgreementManager
      .create("DUA1", "Lots of legal text")
      .await
      .value
    val a2 = secureContainer.dataUseAgreementManager
      .create("DUA2", "Lots of legal text")
      .await
      .value
    get(
      s"/${loggedInOrganization.nodeId}/data-use-agreements",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[DataUseAgreementDTO]].map(_.id) shouldBe List(
        a1.id,
        a2.id
      )
    }
  }

  test("delete unused data use agreement") {
    val a = secureContainer.dataUseAgreementManager
      .create("DUA1", "Lots of legal text")
      .await
      .value
    delete(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${a.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }
  }

  test("update data use agreement") {
    val a = secureContainer.dataUseAgreementManager
      .create("DUA1", "Lots of legal text")
      .await
      .value
    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${a.id}",
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
  }

  test("update data use agreement body") {
    val a = secureContainer.dataUseAgreementManager
      .create("DUA1", "Lots of legal text")
      .await
      .value
    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${a.id}",
      write(UpdateDataUseAgreementRequest(body = Some("Updated text"))),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
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
    secureContainer.dataUseAgreementManager
      .create("DUA1", "Lots of legal text", isDefault = true)
      .await
      .value
    val a2 = secureContainer.dataUseAgreementManager
      .create("DUA2", "Lots of legal text", isDefault = false)
      .await
      .value
    putJson(
      s"/${loggedInOrganization.nodeId}/data-use-agreements/${a2.id}",
      write(UpdateDataUseAgreementRequest(isDefault = Some(true))),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }
  }

  test("non-guest user organization isGuest should be false") {
    get(s"", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("\"isGuest\":false")
    }
  }

  test("guest user organization isGuest should be true") {
    get(s"", headers = authorizationHeader(guestJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("\"isGuest\":true")
    }
  }

  test("non-guest user can see organization owner and admin lists") {
    get(s"", headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()) {
      status should equal(200)
      body shouldNot include("\"administrators\":[]")
    }
  }

  test("guest user cannot see organization owner and admin list") {
    get(s"", headers = authorizationHeader(guestJwt) ++ traceIdHeader()) {
      status should equal(200)
      body should include("\"owners\":[]")
      body should include("\"administrators\":[]")
    }
  }

  test("guest user cannot be added to a team") {
    val team = teamManager.create("NoGuests", loggedInOrganization).await.value
    val req = write(AddToTeamRequest(List(guestUser.nodeId)))
    postJson(
      s"/${loggedInOrganization.nodeId}/teams/${team.nodeId}/members",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(403)
    }
  }

  // Tests pinning down behaviors that depend on flows we haven't fully wired
  // up in the fakes (demo-org gating, package-owner reassignment on member
  // removal, custom ToS lifecycle, complex cross-org scoping). Mark pending
  // for the first migration pass.
  test("get an organization with a JWT")(pending)
  test("demo user should not be able to update the demo organization")(pending)
  test("demo organization user cannot get organization members")(pending)
  test("demo organization user cannot get users from a team")(pending)
  test("add an organization member")(pending)
  test("Cannot remove an integration member")(pending)
  test("refresh an expired invite in Cognito")(pending)
  test("put invite where email is already a user")(pending)
  test("reassign package owner when a user is removed")(pending)
  test("clear package owner when a user is removed and org owner is missing")(
    pending
  )
  test("get custom terms succeeds")(pending)
  test("new custom terms supersedes old")(pending)
  test(
    "demo users should not be able to create dataset options for the demo organization"
  )(pending)
  test("delete dataset status options for organization")(pending)
  test("cannot delete all dataset status options")(pending)
  test(
    "dataset status options are scoped to organization in URL, not secureContainer"
  )(pending)
  test("demo user cannot create data use agreements for organization")(pending)
  test("demo user cannot update data use agreement")(pending)
  test("cannot delete data use agreement that is used by a dataset")(pending)
  test(
    "cannot delete data use agreement that has been signed, even if not used for dataset"
  )(pending)
}
