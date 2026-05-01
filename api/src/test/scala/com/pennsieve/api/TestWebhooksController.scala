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
import com.pennsieve.core.utilities.slugify
import com.pennsieve.dtos.{ APITokenSecretDTO, WebhookDTO, WebhookTargetDTO }
import com.pennsieve.helpers.{ APIContainers, MockAuditLogger }
import com.pennsieve.models.{
  CognitoId,
  DBPermission,
  IntegrationTarget,
  NodeCodes,
  Organization,
  User,
  Webhook
}
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.concurrent.ExecutionContext

class TestWebhooksController extends BaseApiUnitTest {

  val auditLogger = new MockAuditLogger()
  override lazy val mockCognito: MockCognito = new MockCognito()

  var loggedInUser: User = _
  var colleagueUser: User = _
  var superAdmin: User = _
  var loggedInOrganization: Organization = _
  var loggedInJwt: String = _
  var colleagueJwt: String = _
  var adminJwt: String = _
  var secureContainer: APIContainers.SecureAPIContainer = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    addServlet(
      new WebhooksController(
        insecureContainer,
        secureContainerBuilder,
        system,
        auditLogger,
        mockCognito,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.clear()
    setUpFixtures()
  }

  private def setUpFixtures(): Unit = {
    val orgId = state.newId()
    loggedInOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "Test Organization",
      slug = "test-org",
      id = orgId
    )
    state.organizations.put(orgId, loggedInOrganization)

    superAdmin = mkUser("super@a.com", isSuperAdmin = true)
    loggedInUser = mkUser("test@test.com")
    colleagueUser = mkUser("colleague@test.com")

    addOrgMember(superAdmin, DBPermission.Administer)
    addOrgMember(loggedInUser, DBPermission.Administer)
    addOrgMember(colleagueUser, DBPermission.Delete)

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    colleagueJwt = mintUserJwt(colleagueUser, loggedInOrganization)
    adminJwt = mintUserJwt(superAdmin, loggedInOrganization)

    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)
  }

  private def mkUser(email: String, isSuperAdmin: Boolean = false): User = {
    val id = state.newId()
    val u = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "first",
      middleInitial = None,
      lastName = "last",
      degree = None,
      credential = "cred",
      color = "",
      url = "http://test.com",
      authyId = 0,
      isSuperAdmin = isSuperAdmin,
      isIntegrationUser = false,
      preferredOrganizationId = None,
      status = true,
      orcidAuthorization = None,
      cognitoId = Some(CognitoId.UserPoolId(UUID.randomUUID())),
      id = id
    )
    state.users.put(id, u)
    u
  }

  private def addOrgMember(user: User, permission: DBPermission): Unit = {
    state.orgUserPermissions
      .put((loggedInOrganization.id, user.id), permission)
    state.orgUsers.put(
      (loggedInOrganization.id, user.id),
      com.pennsieve.models
        .OrganizationUser(loggedInOrganization.id, user.id, permission)
    )
  }

  /** Create a webhook through the controller's create endpoint then read it
    * back; returns (webhook, subscribedEvents). */
  private def createWebhook(
    apiUrl: String = "https://www.api.com",
    imageUrl: Option[String] = Some("https://www.image.com"),
    description: String = "test webhook",
    secret: String = "secretkey123",
    displayName: String = "Test Webhook",
    targetEvents: Option[List[String]] = Some(List("METADATA", "FILES")),
    customTargets: Option[List[WebhookTargetDTO]] = None,
    isPrivate: Boolean = false,
    isDefault: Boolean = false,
    hasAccess: Boolean = false,
    container: APIContainers.SecureAPIContainer = null
  )(implicit
    ec: ExecutionContext
  ): (Webhook, Seq[String]) = {
    val c = if (container == null) secureContainer else container

    // Replicate the controller-side create flow directly via fakes so each
    // call gets a fresh integration user (mirrors the real createWebhook
    // helper used by the original spec).
    val integrationUser = c.userManager
      .createIntegrationUser(
        User(
          NodeCodes.generateId(NodeCodes.userCode),
          "",
          firstName = "Integration",
          middleInitial = None,
          lastName = "User",
          degree = None,
          credential = "cred",
          color = "",
          url = "",
          authyId = 0,
          isSuperAdmin = false,
          isIntegrationUser = true,
          preferredOrganizationId = None,
          status = true,
          orcidAuthorization = None,
          cognitoId = None
        )
      )
      .await
      .value

    insecureContainer.organizationManager
      .addUser(loggedInOrganization, integrationUser, DBPermission.Administer)
      .await
      .value

    insecureContainer.tokenManager
      .create(
        name = "Integration-user",
        user = integrationUser,
        organization = loggedInOrganization,
        cognitoClient = mockCognito
      )
      .await
      .value

    c.webhookManager
      .create(
        apiUrl = apiUrl,
        imageUrl = imageUrl,
        description = description,
        secret = secret,
        displayName = displayName,
        isPrivate = isPrivate,
        isDefault = isDefault,
        hasAccess = hasAccess,
        targetEvents = targetEvents,
        customTargets = customTargets,
        integrationUser = integrationUser
      )
      .await
      .value
  }

  private def checkProperties(
    webserviceResponse: WebhookDTO,
    expectedWebhook: Webhook,
    expectedEvents: Seq[String]
  ): Unit = {
    webserviceResponse.apiUrl should equal(expectedWebhook.apiUrl)
    webserviceResponse.imageUrl should equal(expectedWebhook.imageUrl)
    webserviceResponse.description should equal(expectedWebhook.description)
    webserviceResponse.customTargets should equal(expectedWebhook.customTargets)

    val (actualWebhook, _) = secureContainer.webhookManager
      .getWithSubscriptions(expectedWebhook.id)
      .await
      .value
    actualWebhook.secret should equal(expectedWebhook.secret)

    webserviceResponse.displayName should equal(expectedWebhook.displayName)
    webserviceResponse.name should equal(slugify(expectedWebhook.displayName))
    webserviceResponse.isPrivate should equal(expectedWebhook.isPrivate)
    webserviceResponse.isDisabled should equal(expectedWebhook.isDisabled)
    webserviceResponse.isDefault should equal(expectedWebhook.isDefault)
    webserviceResponse.id should equal(expectedWebhook.id)
    webserviceResponse.createdAt should equal(expectedWebhook.createdAt)
    webserviceResponse.createdBy should equal(expectedWebhook.createdBy)
    webserviceResponse.tokenSecret shouldBe None

    if (expectedEvents.isEmpty)
      webserviceResponse.eventTargets shouldBe None
    else
      webserviceResponse.eventTargets.value should contain theSameElementsAs expectedEvents
  }

  // ----------- Tests -------------------------------------------------------

  test("get a webhook") {
    val (webhook, subscriptions) = createWebhook()

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val resp = parsedBody.extract[WebhookDTO]
      checkProperties(resp, webhook, subscriptions)
    }
  }

  test("get a list of webhooks") {
    createWebhook(displayName = "Public webhook 1", targetEvents = None)
    createWebhook(displayName = "Public webhook 2")
    createWebhook(displayName = "Private webhook 1 ", isPrivate = true)

    get("", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
      parsedBody.extract[List[WebhookDTO]].length should equal(3)
    }

    get("", headers = authorizationHeader(colleagueJwt)) {
      status shouldBe (200)
      val resp = parsedBody.extract[List[WebhookDTO]]
      resp.length should equal(2)
      resp.map(_.isPrivate).forall(_ == false) should equal(true)
    }
  }

  test("can't get a webhook that doesn't exist") {
    get("/1", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include("Webhook (1) not found")
    }
  }

  test("can't get a private webhook created by a different user") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FILES")),
        customTargets = null,
        hasAccess = false,
        isPrivate = true,
        isDefault = true
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      val webhook = parsedBody.extract[WebhookDTO]

      get(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
        status shouldBe (403)
        body should include(
          s"user ${colleagueUser.id} does not have access to webhook ${webhook.id}"
        )
      }
    }
  }

  test("create a webhook") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FILES")),
        customTargets =
          Some(List(WebhookTargetDTO(IntegrationTarget.PACKAGE, null))),
        hasAccess = false,
        isPrivate = false,
        isDefault = true
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      val webhook = parsedBody.extract[WebhookDTO]
      webhook.apiUrl should equal("https://www.api.com")
      webhook.imageUrl should equal(Some("https://www.image.com"))
      webhook.description should equal("something something")
      webhook.name should equal("TEST_WEBHOOK")
      webhook.displayName should equal("Test Webhook")
      webhook.isPrivate should equal(false)
      webhook.isDefault should equal(true)
      webhook.isDisabled should equal(false)
      webhook.createdBy should equal(loggedInUser.id)
      webhook.tokenSecret.get shouldBe a[APITokenSecretDTO]
      webhook.customTargets should equal(
        Some(List(WebhookTargetDTO(IntegrationTarget.PACKAGE, None)))
      )

      get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
        status should equal(200)
        parsedBody.extract[WebhookDTO].id shouldBe (webhook.id)
      }
    }
  }

  test("Can't create a webhook for non-existing targetEvents") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FAKETARGET")),
        customTargets = null,
        isPrivate = false,
        isDefault = true,
        hasAccess = false
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
    }
  }

  test("can't create a webhook without an api url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FILES")),
        customTargets = null,
        isPrivate = false,
        isDefault = true,
        hasAccess = false
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
    }
  }

  test("can create a webhook without an image url") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = None,
        description = "something something",
        secret = "secretkey",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FILES")),
        customTargets = null,
        isPrivate = false,
        isDefault = true,
        hasAccess = false
      )
    )

    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[WebhookDTO].imageUrl shouldBe None
    }
  }

  test("can't create a webhook without a secret key") {
    val req = write(
      CreateWebhookRequest(
        apiUrl = "https://www.api.com",
        imageUrl = Some("https://www.image.com"),
        description = "something something",
        secret = "",
        displayName = "Test Webhook",
        targetEvents = Some(List("METADATA", "FILES")),
        customTargets = null,
        isPrivate = false,
        isDefault = true,
        hasAccess = false
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("secret must be between 1 and 255 characters")
    }
  }

  test("delete a webhook") {
    val (webhook, _) = createWebhook()

    val integrationUsers = insecureContainer.organizationManager
      .getUsers(loggedInOrganization)
      .await
      .value
    assert(integrationUsers.map(_.id) contains webhook.integrationUserId)

    val integrationToken = insecureContainer.tokenManager
      .getByUserId(webhook.integrationUserId)
      .await
      .value
    assert(integrationToken.userId == webhook.integrationUserId)

    delete(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      parsedBody.extract[Int] should equal(1)
    }
    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include(s"Webhook (${webhook.id}) not found")
    }

    val noOrgUser = insecureContainer.organizationManager
      .getUsers(loggedInOrganization)
      .await
      .value
    assert(!(noOrgUser.map(_.id) contains webhook.integrationUserId))

    val noUserToken = insecureContainer.tokenManager
      .getByUserId(webhook.integrationUserId)
      .await
    assert(noUserToken.isLeft)
  }

  test("can't delete a webhook that doesn't exist") {
    delete("/1", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
      body should include("Webhook (1) not found")
    }
  }

  test("can't delete another user's webhook") {
    val (webhook, _) = createWebhook()

    delete(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have Administer for Webhook (${webhook.id})"
      )
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
    }
  }

  test("super admin can delete another user's webhook") {
    val (webhook, _) = createWebhook()

    delete(s"/${webhook.id}", headers = authorizationHeader(adminJwt)) {
      status should equal(200)
      parsedBody.extract[Int] should equal(1)
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (404)
    }
  }

  test("organization admin can delete another user's webhook") {
    val colleagueContainer =
      secureContainerBuilder(colleagueUser, loggedInOrganization)
    val (webhook, _) = createWebhook(container = colleagueContainer)

    loggedInUser.isSuperAdmin should be(false)

    delete(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      parsedBody.extract[Int] should equal(1)
    }

    get(s"/${webhook.id}", headers = authorizationHeader(colleagueJwt)) {
      status shouldBe (404)
    }
  }

  test("update api url") {
    val (webhook, subscriptions) =
      createWebhook(apiUrl = "https://example.com/api")
    val newApiUrl = webhook.apiUrl + "/v2"
    val req = write(UpdateWebhookRequest(apiUrl = Some(newApiUrl)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      val expectedWebhook = webhook.copy(apiUrl = newApiUrl)
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("update image url") {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("https://example.com/image1.jpg"))
    val newImageUrl = Some("https://example.com/image2.png")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(imageUrl = newImageUrl)
      val updatedWebhook = parsedBody.extract[WebhookDTO]
      checkProperties(updatedWebhook, expectedWebhook, subscriptions)
    }
  }

  test("update the custom target") {
    val (webhook, subscriptions) =
      createWebhook(
        customTargets =
          Some(List(WebhookTargetDTO(IntegrationTarget.PACKAGE, None)))
      )
    val newTarget =
      Some(List(WebhookTargetDTO(IntegrationTarget.RECORD, None)))
    val req = write(UpdateWebhookRequest(customTargets = newTarget))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(customTargets = newTarget)
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("remove custom targets") {
    val (webhook, subscriptions) =
      createWebhook(
        customTargets =
          Some(List(WebhookTargetDTO(IntegrationTarget.PACKAGE, None)))
      )
    val newTargets = Some(List.empty[WebhookTargetDTO])
    val req = write(UpdateWebhookRequest(customTargets = newTargets))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook =
        webhook.copy(customTargets = Some(List.empty[WebhookTargetDTO]))
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("remove image url") {
    val (webhook, subscriptions) =
      createWebhook(imageUrl = Some("https://example.com/image1.jpg"))
    val newImageUrl = Some("")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(imageUrl = None)
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update description") {
    val (webhook, subscriptions) =
      createWebhook(description = "original description")
    val req =
      write(UpdateWebhookRequest(description = Some("a new description")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(description = "a new description")
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update secret") {
    val (webhook, subscriptions) = createWebhook(secret = "xyz123")
    val req = write(UpdateWebhookRequest(secret = Some("123xyz")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(secret = "123xyz")
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update display name") {
    val (webhook, subscriptions) = createWebhook(displayName = "webhook name")
    val req =
      write(UpdateWebhookRequest(displayName = Some("new webhook name")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(displayName = "new webhook name")
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update isPrivate") {
    val (webhook, subscriptions) = createWebhook(isPrivate = true)
    val req = write(UpdateWebhookRequest(isPrivate = Some(!webhook.isPrivate)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(isPrivate = !webhook.isPrivate)
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update isDefault") {
    val (webhook, subscriptions) = createWebhook(isDefault = true)
    val req = write(UpdateWebhookRequest(isDefault = Some(!webhook.isDefault)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(isDefault = !webhook.isDefault)
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update isDisabled") {
    val (webhook, subscriptions) = createWebhook()
    val req =
      write(UpdateWebhookRequest(isDisabled = Some(!webhook.isDisabled)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val expectedWebhook = webhook.copy(isDisabled = !webhook.isDisabled)
      checkProperties(
        parsedBody.extract[WebhookDTO],
        expectedWebhook,
        subscriptions
      )
    }
  }

  test("update events") {
    val (webhook, _) =
      createWebhook(targetEvents = Some(List("METADATA", "STATUS")))
    val newSubscriptions = List("STATUS", "FILES")
    val req = write(UpdateWebhookRequest(targetEvents = Some(newSubscriptions)))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      checkProperties(parsedBody.extract[WebhookDTO], webhook, newSubscriptions)
    }
  }

  test("remove all events") {
    val (webhook, _) =
      createWebhook(targetEvents = Some(List("METADATA", "STATUS")))
    val req = write(UpdateWebhookRequest(targetEvents = Some(List())))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      checkProperties(parsedBody.extract[WebhookDTO], webhook, List())
    }
  }

  test("can't remove api url") {
    val (webhook, _) = createWebhook()
    val req = write(UpdateWebhookRequest(apiUrl = Some("")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
    }
  }

  test("get correct error if updated api url is too long") {
    val (webhook, _) = createWebhook()
    val newApiUrl = Some("http://" + "example" * 400 + ".com")
    val req = write(UpdateWebhookRequest(apiUrl = newApiUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("api url must be between 1 and 255 characters")
    }
  }

  test("get correct error if updated image url is too long") {
    val (webhook, _) = createWebhook()
    val newImageUrl = Some("http://" + "example" * 400 + ".com/image.jpg")
    val req = write(UpdateWebhookRequest(imageUrl = newImageUrl))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("image url")
    }
  }

  test("get correct error if updated event does not exist") {
    val (webhook, _) = createWebhook()
    val req =
      write(UpdateWebhookRequest(targetEvents = Some(List("NON-EVENT"))))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("unknown event name")
    }
  }

  test("get not found response if updating a webhook that does not exist") {
    val req =
      write(UpdateWebhookRequest(apiUrl = Some("https://example.com/api")))

    putJson(s"/15", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(404)
      body should include(s"Webhook (15) not found")
    }
  }

  test("can't update another user's webhook") {
    val (webhook, _) = createWebhook(apiUrl = "https://example.com/api/v1")
    val req =
      write(UpdateWebhookRequest(apiUrl = Some("https://example.com/api/v2")))

    putJson(s"/${webhook.id}", req, headers = authorizationHeader(colleagueJwt)) {
      status should equal(403)
      body should include(
        s"${colleagueUser.nodeId} does not have Administer for Webhook (${webhook.id})"
      )
    }

    get(s"/${webhook.id}", headers = authorizationHeader(loggedInJwt)) {
      status shouldBe (200)
    }
  }
}
