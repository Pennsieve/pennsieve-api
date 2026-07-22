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

package com.pennsieve.helpers.fakes

import com.pennsieve.db.{ CustomTermsOfService, PennsieveTermsOfService }
import com.pennsieve.models.{
  Collection,
  Contributor,
  DBPermission,
  Feature,
  Organization,
  OrganizationUser,
  Token,
  User
}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

/**
  * Shared in-memory state that fake managers read from and write to.
  *
  * Tests construct one InMemoryState per spec and pass it to every Fake*Manager
  * they wire up — that way fakes can interact (e.g., FakeOrganizationManager
  * sees a user FakeUserManager just created).
  */
class InMemoryState {
  val organizations: TrieMap[Int, Organization] = new TrieMap()
  val users: TrieMap[Int, User] = new TrieMap()
  val orgUsers: TrieMap[(Int, Int), OrganizationUser] = new TrieMap()
  val orgUserPermissions: TrieMap[(Int, Int), DBPermission] = new TrieMap()
  val tokens: TrieMap[String, Token] = new TrieMap()

  // Collections are scoped to an organization; keyed by (orgId, collectionId).
  val collections: TrieMap[(Int, Int), Collection] = new TrieMap()

  // Contributors are scoped to an organization; keyed by (orgId, contributorId).
  val contributors: TrieMap[(Int, Int), Contributor] = new TrieMap()

  // Active feature flags per organization. `(orgId, feature) -> true` means
  // the flag is enabled. Tests configure as needed.
  val featureFlags: TrieMap[(Int, Feature), Boolean] = new TrieMap()

  // Pennsieve ToS acceptance per user.
  val pennsieveTos: TrieMap[Int, PennsieveTermsOfService] = new TrieMap()

  // Custom ToS acceptance per (userId, organizationId).
  val customTos: TrieMap[(Int, Int), CustomTermsOfService] = new TrieMap()

  private val ids = new AtomicInteger(1)
  def newId(): Int = ids.getAndIncrement()
}
