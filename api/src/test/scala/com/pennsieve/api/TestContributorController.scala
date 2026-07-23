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

import com.pennsieve.domain.PredicateError
import com.pennsieve.dtos.ContributorDTO
import com.pennsieve.helpers._
import com.pennsieve.models.{
  Contributor,
  DBPermission,
  Degree,
  Feature,
  NodeCodes,
  OrcidAuthorization,
  Organization,
  User
}
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write

import scala.concurrent.Future

class TestContributorController extends BaseApiUnitTest {

  val testOrcid = "0000-0001-1234-5678"

  val orcidAuthorization: OrcidAuthorization = OrcidAuthorization(
    name = "name",
    accessToken = "accessToken",
    expiresIn = 100,
    tokenType = "tokenType",
    orcid = "orcid",
    scope = "scope",
    refreshToken = "refreshToken"
  )

  val orcidClient: OrcidClient = new OrcidClient {
    override def verifyOrcid(orcid: Option[String]): Future[Boolean] =
      orcid match {
        case None => Future.successful(true)
        case Some(o) if o == testOrcid => Future.successful(true)
        case _ => Future.failed(PredicateError("ORCID not found"))
      }

    override def getToken(
      authorizationCode: String
    ): Future[OrcidAuthorization] =
      Future.successful(orcidAuthorization)

    override def publishWork(
      work: OrcidWorkPublishing
    ): Future[Option[String]] =
      Future.successful(Some("1234567"))

    override def unpublishWork(work: OrcidWorkUnpublishing): Future[Boolean] =
      Future.successful(true)
  }

  private val loggedInUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000001",
    email = "loggedin@test.com",
    firstName = "LoggedIn",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 1
  )

  private val colleagueUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000002",
    email = "colleague@test.com",
    firstName = "Colleague",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 2
  )

  private val sandboxUser: User = User(
    nodeId = "N:user:00000000-0000-0000-0000-000000000003",
    email = "sandbox@test.com",
    firstName = "Sandbox",
    middleInitial = None,
    lastName = "User",
    degree = None,
    credential = "",
    color = "",
    url = "",
    isSuperAdmin = false,
    id = 3
  )

  private val loggedInOrganization: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000001",
    name = "Test Organization",
    slug = "test-organization",
    encryptionKeyId = Some("test-key"),
    id = 1
  )

  private val sandboxOrganization: Organization = Organization(
    nodeId = "N:organization:00000000-0000-0000-0000-000000000002",
    name = "Sandbox Organization",
    slug = "sandbox-organization",
    encryptionKeyId = Some("sandbox-key"),
    id = 2
  )

  private var loggedInJwt: String = _
  private var sandboxUserJwt: String = _

  private var preexistingContributorId: Int = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    addServlet(
      new ContributorsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher,
        orcidClient
      ),
      "/*"
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.users.clear()
    state.organizations.clear()
    state.orgUsers.clear()
    state.orgUserPermissions.clear()
    state.contributors.clear()
    state.featureFlags.clear()

    state.users.put(loggedInUser.id, loggedInUser)
    state.users.put(colleagueUser.id, colleagueUser)
    state.users.put(sandboxUser.id, sandboxUser)
    state.organizations.put(loggedInOrganization.id, loggedInOrganization)
    state.organizations.put(sandboxOrganization.id, sandboxOrganization)
    state.orgUserPermissions
      .put((loggedInOrganization.id, loggedInUser.id), DBPermission.Owner)
    state.orgUserPermissions
      .put((loggedInOrganization.id, colleagueUser.id), DBPermission.Delete)
    state.orgUserPermissions
      .put((sandboxOrganization.id, sandboxUser.id), DBPermission.Owner)
    state.featureFlags
      .put((sandboxOrganization.id, Feature.SandboxOrgFeature), true)

    // Mirrors the BaseApiTest baseline: there's already one contributor in
    // the org from the seeded dataset's creation flow ("first"/"last"/"test@test.com").
    val pre = Contributor(
      firstName = Some("first"),
      lastName = Some("last"),
      email = Some("test@test.com"),
      middleInitial = None,
      degree = None,
      orcid = None,
      userId = None,
      id = 1
    )
    state.contributors.put((loggedInOrganization.id, pre.id), pre)
    preexistingContributorId = pre.id
    // bump the id sequence so subsequent newId() values don't collide
    while (state.newId() < 100) ()

    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    sandboxUserJwt = mintUserJwt(sandboxUser, sandboxOrganization)
  }

  /** Seed a contributor directly in state (replaces DataSetTestMixin.createContributor). */
  private def createContributor(
    firstName: String,
    lastName: String,
    email: String,
    middleInitial: Option[String] = None,
    degree: Option[Degree] = None,
    orcid: Option[String] = None,
    userId: Option[Int] = None
  ): ContributorDTO = {
    val checked = com.pennsieve.core.utilities
      .checkAndNormalizeInitial(middleInitial)
      .toOption
      .getOrElse(throw new IllegalArgumentException("middle initial too long"))
    val c = Contributor(
      firstName = if (firstName.trim.isEmpty) None else Some(firstName.trim),
      lastName = if (lastName.trim.isEmpty) None else Some(lastName.trim),
      email = if (email.trim.isEmpty) None else Some(email.trim),
      middleInitial = checked,
      degree = degree,
      orcid = orcid,
      userId = userId,
      id = state.newId()
    )
    state.contributors.put((loggedInOrganization.id, c.id), c)
    val u = c.userId.flatMap(state.users.get)
    ContributorDTO((c, u))
  }

  /** Seeds a user into the org, mirroring DataSetTestMixin.createUser. Returns an
    * OrganizationUser-shaped pair (userId, nodeId) that tests use. */
  private def createUser(
    firstName: String,
    lastName: String,
    email: String,
    nodeId: Option[String] = None,
    orcid: Option[String] = None
  ): OrganizationUserView = {
    val orcidAuth = orcid.map(
      o =>
        OrcidAuthorization(
          "name",
          "accessToken",
          10000,
          "tokenType",
          o,
          "scope",
          "refreshToken"
        )
    )
    val newId = state.newId()
    val user = User(
      nodeId = nodeId.getOrElse(NodeCodes.generateId(NodeCodes.userCode)),
      // Match the Postgres `lowercase_email_on_insert_trigger`.
      email = email.toLowerCase,
      firstName = firstName,
      middleInitial = None,
      lastName = lastName,
      degree = None,
      credential = "cred",
      color = "",
      url = "http://blind.com",
      isSuperAdmin = false,
      orcidAuthorization = orcidAuth,
      id = newId
    )
    state.users.put(user.id, user)
    state.orgUserPermissions
      .put((loggedInOrganization.id, user.id), DBPermission.Delete)
    // Side-effect: upgrade any contributor with matching email (mirrors
    // OrganizationManager.addUser → upgradeContributor).
    if (user.email.nonEmpty) {
      state.contributors.foreach {
        case (key @ (orgId, _), c)
            if orgId == loggedInOrganization.id &&
              c.email.exists(_.equalsIgnoreCase(user.email)) =>
          state.contributors.put(key, c.copy(userId = Some(user.id)))
        case _ => ()
      }
    }
    OrganizationUserView(userId = user.id, nodeId = user.nodeId)
  }

  case class OrganizationUserView(userId: Int, nodeId: String)

  test("get a contributor") {
    val ct1 = createContributor(
      firstName = "Test",
      lastName = "Contributor",
      email = "test-contributor@bf.com",
      middleInitial = Some("Q"),
      degree = Some(Degree.PhD)
    )
    val ct2 = createContributor(
      firstName = "Test 2",
      lastName = "Contributor 2",
      email = "test-contributor-2@bf.com",
      middleInitial = Some(""),
      degree = Some(Degree.PhD)
    )

    get(s"/${ct1.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      (parsedBody \ "firstName").extract[String] should equal("Test")
      (parsedBody \ "lastName").extract[String] should equal("Contributor")
      (parsedBody \ "email").extract[String] should equal(
        "test-contributor@bf.com"
      )
      (parsedBody \ "middleInitial").extractOpt[String] should equal(Some("Q"))
      (parsedBody \ "degree").extractOpt[String] should equal(Some("Ph.D."))
      (parsedBody \ "orcid").extractOpt[String] should equal(None)
      (parsedBody \ "id").extract[Int] should equal(ct1.id)
    }

    get(s"/${ct2.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      (parsedBody \ "middleInitial").extractOpt[String] should equal(None)
    }

    val ct3 = scala.util.Try {
      createContributor(
        firstName = "Test 3",
        lastName = "Contributor 3",
        email = "test-contributor-3@bf.com",
        middleInitial = Some("TOO LONG"), // will fail
        degree = Some(Degree.PhD)
      )
    }
    assert(ct3.isFailure)
  }

  test(
    "create and get a contributor who is also a user - validating user/contributor merging rules"
  ) {
    val ct2 = createContributor(
      firstName = "John",
      lastName = "Doe",
      email = "j.doe@bf.com",
      degree = Some(Degree.PhD),
      middleInitial = Some("K"),
      orcid = Some("Fake-Orcid"),
      userId = Some(colleagueUser.id)
    )

    get(s"/${ct2.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      (parsedBody \ "firstName").extract[String] should equal(
        colleagueUser.firstName
      )
      (parsedBody \ "lastName").extract[String] should equal(
        colleagueUser.lastName
      )
      (parsedBody \ "email").extract[String] should equal(colleagueUser.email)
      (parsedBody \ "middleInitial").extractOpt[String] should equal(Some("K"))
      (parsedBody \ "degree").extractOpt[String] should equal(Some("Ph.D."))
      (parsedBody \ "orcid").extractOpt[String] should equal(Some("Fake-Orcid"))
      (parsedBody \ "id").extract[Int] should equal(ct2.id)
    }
  }

  test("get all contributors of the current Org") {
    get(s"/", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val contributors = parsedBody.extract[List[ContributorDTO]]

      contributors.length should equal(1)
      contributors(0).firstName should equal("first")
      contributors(0).lastName should equal("last")
      contributors(0).email should equal("test@test.com")
      contributors(0).id should equal(preexistingContributorId)
    }
  }

  test("can't get any contributors if the current org is the demo org") {
    val b1 =
      write(CreateContributorRequest("test", "testerson", "test@gmail.com"))
    postJson(s"/", body = b1, headers = authorizationHeader(sandboxUserJwt)) {
      status should equal(201)
    }
    val b2 =
      write(CreateContributorRequest("test2", "testerson2", "test2@gmail.com"))
    postJson(s"/", body = b2, headers = authorizationHeader(sandboxUserJwt)) {
      status should equal(201)
    }

    get(s"/", headers = authorizationHeader(sandboxUserJwt)) {
      status should equal(200)
      val contributors = parsedBody.extract[List[ContributorDTO]]
      contributors.length should equal(0)
    }
  }

  test("can't create a contributor with incorrect ORCID") {
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "troye+sivan@pennsieve.org",
        orcid = Some("plop")
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(404)
      body should include("ORCID not found")
    }
  }

  test("can create a contributor with empty ORCID (treated as no ORCID)") {
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "troye+sivan@pennsieve.org",
        orcid = Some("")
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
    }
  }

  test("can't create a contributor with incorrect email") {
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "incorrectEmail"
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("improper email format")
    }
  }

  test("first name is mandatory if no userId") {
    val req = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "Sivan",
        email = "troye.sivan@pennsieve.org"
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include(
        "first name of contributor must not be empty if not already a user"
      )
    }
  }

  test("last name is mandatory if no userId") {
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "",
        email = "troye.sivan@pennsieve.org"
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include(
        "last name of contributor must not be empty if not already a user"
      )
    }
  }

  test("email is mandatory if no userId") {
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = ""
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include(
        "email of contributor must not be empty if not already a user"
      )
    }
  }

  test(
    "can't create contributor if email is already used by another contributor"
  ) {
    // The set-up of the test seeds a contributor with test@test.com email.
    // Also tests case-insensitivity of email lookup.
    val req = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "TEST@TesT.COM"
      )
    )
    postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("email must be unique")
    }
  }

  test(
    "can't create contributor if email is already used by another contributor who is also a User"
  ) {
    val first = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "",
        email = "",
        userId = Some(colleagueUser.id)
      )
    )
    postJson(s"/", first, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
    }

    val second = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "",
        email = colleagueUser.email
      )
    )
    postJson(s"/", second, headers = authorizationHeader(loggedInJwt)) {
      status should equal(400)
      body should include("email must be unique")
    }
  }

  test(
    "can create contributor then the same email is used to create a User: contributor automatically get linked to the user"
  ) {
    val req = write(
      CreateContributorRequest(
        firstName = "Pol",
        lastName = "Jon",
        email = "m.urie@star.com"
      )
    )
    val ct1 = postJson(s"/", req, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    // Register a User with the same email — this triggers contributor auto-link
    // via the addUser side effect in FakeSecureOrganizationManager.
    val newUser = createUser("Michael", "Urie", "m.urie@star.com")

    get(s"/${ct1.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Michael")
      contributor.lastName should equal("Urie")
      contributor.email should equal("m.urie@star.com")
      contributor.orcid should equal(None)
      contributor.userId should equal(Some(newUser.userId))
    }
  }

  test("edit contributor") {
    // Re-add Michael from previous test
    createUser("Michael", "Urie", "m.urie@star.com")

    val userWithNoOrcidNodeId = NodeCodes.generateId(NodeCodes.userCode)
    val userWithNoOrcid = createUser(
      "Ben",
      "Platt",
      "userNoOrcid@test.com",
      nodeId = Some(userWithNoOrcidNodeId)
    )

    val userWithOrcidNodeId = NodeCodes.generateId(NodeCodes.userCode)
    val userWithOrcid = createUser(
      "Noah",
      "Galvin",
      "userOrcid@test.com",
      nodeId = Some(userWithOrcidNodeId),
      orcid = Some(testOrcid)
    )

    val req1 = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "",
        email = "",
        userId = Some(userWithNoOrcid.userId)
      )
    )
    val ct1 = postJson(s"/", req1, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateFirstName = write(
      UpdateContributorRequest(
        firstName = Some("First Name"),
        lastName = None,
        middleInitial = None,
        degree = None,
        orcid = None
      )
    )
    putJson(
      s"/${ct1.id}",
      updateFirstName,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user. Only the user can change their own first name"
      )
    }

    val updateLastName = write(
      UpdateContributorRequest(
        firstName = None,
        lastName = Some("Last Name"),
        middleInitial = None,
        degree = None,
        orcid = None
      )
    )
    putJson(
      s"/${ct1.id}",
      updateLastName,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user. Only the user can change their own last name"
      )
    }

    val updateOrcid = write(
      UpdateContributorRequest(
        firstName = None,
        lastName = None,
        middleInitial = None,
        degree = None,
        orcid = Some(testOrcid)
      )
    )
    putJson(
      s"/${ct1.id}",
      updateOrcid,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Ben")
      contributor.lastName should equal("Platt")
      contributor.email should equal("usernoorcid@test.com")
      contributor.orcid should equal(Some(testOrcid))
      contributor.id should equal(ct1.id)
    }

    // Clear the ORCID
    putJson(
      s"/${ct1.id}",
      """{ "orcid": null }""",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Ben")
      contributor.lastName should equal("Platt")
      contributor.email should equal("usernoorcid@test.com")
      contributor.id should equal(ct1.id)
      contributor.orcid should equal(None)
    }

    val req2 = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "",
        email = "",
        userId = Some(userWithOrcid.userId)
      )
    )
    val ct2 = postJson(s"/", req2, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateOrcidUserHas = write(
      UpdateContributorRequest(
        firstName = None,
        lastName = None,
        middleInitial = None,
        degree = None,
        orcid = Some(testOrcid)
      )
    )
    putJson(
      s"/${ct2.id}",
      updateOrcidUserHas,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user and has defined his ORCID. Only the user can change this value"
      )
    }

    val req3 = write(
      CreateContributorRequest(
        firstName = "john",
        lastName = "paul",
        email = "ian.mckellen@lotr.com"
      )
    )
    val ct3 = postJson(s"/", req3, headers = authorizationHeader(loggedInJwt)) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateNonUser = write(
      UpdateContributorRequest(
        firstName = Some("Ian"),
        lastName = Some("McKellen"),
        orcid = Some(testOrcid),
        middleInitial = None,
        degree = None
      )
    )
    putJson(
      s"/${ct3.id}",
      updateNonUser,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Ian")
      contributor.lastName should equal("McKellen")
      contributor.email should equal("ian.mckellen@lotr.com")
      contributor.orcid should equal(Some(testOrcid))
      contributor.id should equal(ct3.id)
    }
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
    }
  }
}
