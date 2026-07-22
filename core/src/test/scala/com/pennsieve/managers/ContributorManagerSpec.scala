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

import com.pennsieve.domain.PredicateError
import com.pennsieve.models.{ DBPermission, OrcidAuthorization }
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Pins down `ContributorManager` validation against real Postgres. Until
  * this spec was added, every validation message asserted by
  * `TestContributorController` was only exercised at the controller layer;
  * the manager's PredicateError messages were not covered in core. This
  * closes the gap required by the controller's migration to a fake-backed
  * unit spec.
  */
class ContributorManagerSpec extends BaseManagerSpec {

  "create" should "require a first name when no userId is given" in {
    val cm = contributorsManager()
    cm.create(
        firstName = "",
        lastName = "Doe",
        email = "j.doe@test.com",
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError(
      "first name of contributor must not be empty if not already a user"
    )
  }

  "create" should "require a last name when no userId is given" in {
    val cm = contributorsManager()
    cm.create(
        firstName = "John",
        lastName = "",
        email = "j.doe@test.com",
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError(
      "last name of contributor must not be empty if not already a user"
    )
  }

  "create" should "require an email when no userId is given" in {
    val cm = contributorsManager()
    cm.create(
        firstName = "John",
        lastName = "Doe",
        email = "",
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError(
      "email of contributor must not be empty if not already a user"
    )
  }

  "create" should "reject syntactically invalid email" in {
    val cm = contributorsManager()
    cm.create(
        firstName = "John",
        lastName = "Doe",
        email = "not-an-email",
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError("improper email format")
  }

  "create" should "reject duplicate email within an organization (case-insensitive)" in {
    val cm = contributorsManager()
    cm.create(
        firstName = "John",
        lastName = "Doe",
        email = "dup@test.com",
        middleInitial = None,
        degree = None
      )
      .await
      .value
    cm.create(
        firstName = "Jane",
        lastName = "Doe",
        email = "DUP@TEST.COM",
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError("email must be unique")
  }

  "create" should "reject email matching a registered user via an existing contributor (uniqueness joins through user.email)" in {
    val user = createUser(email = "joined@test.com")
    val cm = contributorsManager()
    // Contributor with no own email but linked to the user; the real query
    // (ContributorTable.getContributorByEmail) joins to users so user.email
    // counts toward uniqueness.
    cm.create(
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.email,
        middleInitial = None,
        degree = None,
        userId = Some(user.id)
      )
      .await
      .value
    cm.create(
        firstName = "Other",
        lastName = "Person",
        email = user.email,
        middleInitial = None,
        degree = None
      )
      .await
      .left
      .value shouldBe PredicateError("email must be unique")
  }

  "updateInfo" should "reject changing first name when contributor is already a registered user" in {
    val user = createUser(email = "fixed-name@test.com")
    val cm = contributorsManager()
    val (contributor, _) = cm
      .create(
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.email,
        middleInitial = None,
        degree = None,
        userId = Some(user.id)
      )
      .await
      .value

    cm.updateInfo(
        firstName = Some("New"),
        lastName = None,
        orcid = None,
        middleInitial = None,
        degree = None,
        contributorId = contributor.id
      )
      .await
      .left
      .value shouldBe PredicateError(
      "this contributor is a registered user. Only the user can change their own first name"
    )
  }

  "updateInfo" should "reject changing last name when contributor is already a registered user" in {
    val user = createUser(email = "fixed-last@test.com")
    val cm = contributorsManager()
    val (contributor, _) = cm
      .create(
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.email,
        middleInitial = None,
        degree = None,
        userId = Some(user.id)
      )
      .await
      .value

    cm.updateInfo(
        firstName = None,
        lastName = Some("New"),
        orcid = None,
        middleInitial = None,
        degree = None,
        contributorId = contributor.id
      )
      .await
      .left
      .value shouldBe PredicateError(
      "this contributor is a registered user. Only the user can change their own last name"
    )
  }

  "updateInfo" should "reject changing ORCID when the registered user already has one" in {
    val user = userManager
      .create(
        com.pennsieve.models.User(
          nodeId = com.pennsieve.models.NodeCodes
            .generateId(com.pennsieve.models.NodeCodes.userCode),
          email = "user-with-orcid@test.com",
          firstName = "Has",
          middleInitial = None,
          lastName = "Orcid",
          degree = None,
          credential = "",
          color = "",
          url = "",
          orcidAuthorization = Some(
            OrcidAuthorization(
              "n",
              "tok",
              100,
              "type",
              "user-orcid",
              "scope",
              "ref"
            )
          )
        )
      )
      .await
      .value
    organizationManager()
      .addUser(testOrganization, user, DBPermission.Delete)
      .await
      .value

    val cm = contributorsManager()
    val (contributor, _) = cm
      .create(
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.email,
        middleInitial = None,
        degree = None,
        userId = Some(user.id)
      )
      .await
      .value

    cm.updateInfo(
        firstName = None,
        lastName = None,
        orcid = Some("new-orcid"),
        middleInitial = None,
        degree = None,
        contributorId = contributor.id
      )
      .await
      .left
      .value shouldBe PredicateError(
      "this contributor is a registered user and has defined his ORCID. Only the user can change this value"
    )
  }
}
