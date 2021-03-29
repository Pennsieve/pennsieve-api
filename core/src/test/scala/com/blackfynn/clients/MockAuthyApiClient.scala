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

package com.pennsieve.clients

import com.authy.AuthyApiClient
import com.authy.api.{ Error, Hash, Token, Tokens, User, Users }

class MockAuthyApiClient(key: String, url: String, debugMode: Boolean)
    extends AuthyApiClient(key, url, debugMode) {

  class MockUsers(uri: String, key: String, testFlag: Boolean)
      extends Users(uri, key, testFlag) {

    override def createUser(
      email: String,
      phone: String,
      countryCode: String
    ): User = {
      val user = new User(200, "")
      user.setId(12345)
      user
    }

    override def deleteUser(userId: Int) = {
      val hash = new Hash()
      hash.setStatus(200)
      hash
    }

  }

  class MockTokens(uri: String, key: String, testFlag: Boolean)
      extends Tokens(uri, key, testFlag) {

    override def verify(userId: Int, token: String): Token = {
      if (token == "0000000")
        new Token(200, "", "Token is valid.")
      else {
        val a = new Token(401, "")
        a.setError(new Error())
        a
      }
    }
  }

  val users: MockUsers = new MockUsers(url, key, debugMode)
  val tokens: MockTokens = new MockTokens(url, key, debugMode)

  override def getUsers: MockUsers = {
    this.users
  }
  override def getTokens: MockTokens = {
    this.tokens
  }
}
