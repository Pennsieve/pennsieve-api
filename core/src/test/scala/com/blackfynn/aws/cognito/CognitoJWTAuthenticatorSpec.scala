package com.blackfynn.aws.cognito

import org.scalatest.{ FlatSpec, Matchers }

class CognitoJWTAuthenticatorSpec extends FlatSpec with Matchers {

  "validateToken" should "return true if supplied token is valid" in {
    CognitoJWTAuthenticator.validateToken("valid_token") should equal(true)
  }

  "validateToken" should "return false if supplied token is invalid" in {
    CognitoJWTAuthenticator.validateToken("invalid_token") should equal(false)
  }

  "getUserIdFromToken" should "return user ID from supplied token payload" in {
    CognitoJWTAuthenticator.getUserIdFromToken(token = "valid_token") should equal(
      "userid"
    )
  }

}
