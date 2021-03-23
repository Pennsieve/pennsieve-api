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
