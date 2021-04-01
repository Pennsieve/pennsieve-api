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

import com.pennsieve.models.{ Degree, PackageType }
import com.pennsieve.domain.PredicateError
import com.pennsieve.dtos.ContributorDTO
import com.pennsieve.helpers._
import io.circe.{ Decoder, Encoder }
import org.json4s._
import org.json4s.jackson.JsonMethods._
import cats.implicits._

import org.json4s.jackson.Serialization.write
import com.pennsieve.models.{
  DBPermission,
  NodeCodes,
  OrcidAuthorization,
  User
}

import scala.concurrent.Future

class TestContributorController extends BaseApiTest with DataSetTestMixin {

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
        case Some(orcid) if (orcid == testOrcid) =>
          Future.successful(true)
        case _ => Future.failed(PredicateError("ORCID not found"))
      }

    override def getToken(
      authorizationCode: String
    ): Future[OrcidAuthorization] =
      Future.successful(orcidAuthorization)
  }

  override def afterStart(): Unit = {
    super.afterStart()

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

  override def afterEach(): Unit = {
    super.afterEach()
  }

  test("get a contributor") {
    val ct1 =
      createContributor(
        firstName = "Test",
        lastName = "Contributor",
        email = "test-contributor@bf.com",
        middleInitial = Some("Q"),
        degree = Some(Degree.PhD)
      )

    val ct2 =
      createContributor(
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

    val ct3 = Either.catchNonFatal {
      createContributor(
        firstName = "Test 3",
        lastName = "Contributor 3",
        email = "test-contributor-3@bf.com",
        middleInitial = Some("TOO LONG"), // will fail
        degree = Some(Degree.PhD)
      )
    }
    assert(ct3.isLeft)
  }

  test(
    "create and get a contributor who is also a user - validating user/contributor merging rules"
  ) {
    val ct2 =
      createContributor(
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
      //merging rules:
      //first name, last name and email comes from the user if userId is provided, from the contributor otherwise and cannot be None
      //orcid, middle Initial and degree come from the user if userId is provided and they are defined at the user level, otherwise, from the contributor but can be None

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

      //we get 1 contributor since baseApiTest also creates a dataset before the beginning of this test
      contributors.length should equal(1)
      contributors(0).firstName should equal("first")
      contributors(0).lastName should equal("last")
      contributors(0).email should equal("test@test.com")
      contributors(0).id should equal(1)
    }
  }

  test("can't create a contributor with incorrect ORCID") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "troye+sivan@pennsieve.org",
        orcid = Some("plop")
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
      body should include("ORCID not found")
    }
  }

  test("can create a contributor with empty ORCID (treated as no ORCID)") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "troye+sivan@pennsieve.org",
        orcid = Some("")
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
    }
  }

  test("can't create a contributor with incorrect email") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = "incorrectEmail"
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("improper email format")
    }
  }

  test("first name is mandatory if no userId") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "Sivan",
        email = "troye.sivan@pennsieve.org"
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "first name of contributor must not be empty if not already a user"
      )
    }
  }

  test("last name is mandatory if no userId") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "",
        email = "troye.sivan@pennsieve.org"
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "last name of contributor must not be empty if not already a user"
      )
    }
  }

  test("email is mandatory if no userId") {

    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Troye",
        lastName = "Sivan",
        email = ""
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "email of contributor must not be empty if not already a user"
      )
    }
  }

  test(
    "can't create contributor if email is already used by another contributor"
  ) {
    //the set-up of the test creates a contributor with test@test.com email
    //also test case insensitivity of email lookup
    val contributorRequest =
      write(
        CreateContributorRequest(
          firstName = "Troye",
          lastName = "Sivan",
          email = "TEST@TesT.COM"
        )
      )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("email must be unique")
    }
  }

  test(
    "can't create contributor if email is already used by another contributor who is also a User"
  ) {
    //create new contributor/user by passing only its nodeId
    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "",
        lastName = "",
        email = "",
        userId = Some(colleagueUser.id)
      )
    )

    postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
    }

    //this time we try to create a contributor with the same email
    val contributorRequest2 =
      write(
        CreateContributorRequest(
          firstName = "",
          lastName = "",
          email = colleagueUser.email
        )
      )

    postJson(
      s"/",
      contributorRequest2,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("email must be unique")
    }
  }

  test(
    "can create contributor then the same email is used to create a User: contributor automatically get linked to the user"
  ) {
    //create new contributor/user by passing only its nodeId
    val contributorRequest = write(
      CreateContributorRequest(
        firstName = "Pol",
        lastName = "Jon",
        email = "m.urie@star.com"
      )
    )

    val ct1 = postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    //we then register the User with the same Organization
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
    //the test removes the users between each test, which messes up with the userId number, In order to get this test working,
    //we need to add the user from the previous test back
    val newUser = createUser("Michael", "Urie", "m.urie@star.com")

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
      orcid = Some("0000-0001-1234-5678")
    )

    //create new contributor/user by passing only its nodeId
    val contributorRequest =
      write(
        CreateContributorRequest(
          firstName = "",
          lastName = "",
          email = "",
          userId = Some(userWithNoOrcid.userId)
        )
      )

    val ct1 = postJson(
      s"/",
      contributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateContributorRequest =
      write(
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
      updateContributorRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user. Only the user can change their own first name"
      )
    }

    val updateContributorRequest2 =
      write(
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
      updateContributorRequest2,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user. Only the user can change their own last name"
      )
    }

    val updateContributorRequest4 =
      write(
        UpdateContributorRequest(
          firstName = None,
          lastName = None,
          middleInitial = None,
          degree = None,
          orcid = Some("0000-0001-1234-5678")
        )
      )

    putJson(
      s"/${ct1.id}",
      updateContributorRequest4,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Ben")
      contributor.lastName should equal("Platt")
      contributor.email should equal("usernoorcid@test.com")
      contributor.orcid should equal(Some("0000-0001-1234-5678"))
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

    val contributorRequest2 =
      write(
        CreateContributorRequest(
          firstName = "",
          lastName = "",
          email = "",
          userId = Some(userWithOrcid.userId)
        )
      )

    val ct2 = postJson(
      s"/",
      contributorRequest2,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateContributorRequest5 =
      write(
        UpdateContributorRequest(
          firstName = None,
          lastName = None,
          middleInitial = None,
          degree = None,
          orcid = Some("0000-0001-1234-5678")
        )
      )

    putJson(
      s"/${ct2.id}",
      updateContributorRequest5,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include(
        "this contributor is a registered user and has defined his ORCID. Only the user can change this value"
      )
    }

    val contributorRequest3 =
      write(
        CreateContributorRequest(
          firstName = "john",
          lastName = "paul",
          email = "ian.mckellen@lotr.com"
        )
      )

    val ct3 = postJson(
      s"/",
      contributorRequest3,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[ContributorDTO]
    }

    val updateContributorRequest6 =
      write(
        UpdateContributorRequest(
          firstName = Some("Ian"),
          lastName = Some("McKellen"),
          orcid = Some("0000-0001-1234-5678"),
          middleInitial = None,
          degree = None
        )
      )

    putJson(
      s"/${ct3.id}",
      updateContributorRequest6,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val contributor = parsedBody.extract[ContributorDTO]
      contributor.firstName should equal("Ian")
      contributor.lastName should equal("McKellen")
      contributor.email should equal("ian.mckellen@lotr.com")
      contributor.orcid should equal(Some("0000-0001-1234-5678"))
      contributor.id should equal(ct3.id)
    }
  }
}
