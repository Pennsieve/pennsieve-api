package com.blackfynn.aws.cognito

import com.blackfynn.utilities.Container
import org.scalatest.{ BeforeAndAfter, FlatSpec, Matchers }
import pdi.jwt.{ JwtAlgorithm, JwtCirce, JwtClaim }

class CognitoJWTAuthenticatorSpec
    extends FlatSpec
    with Matchers
    with BeforeAndAfter {

  var testToken: String =
    JwtCirce.encode(JwtClaim("claim"))
  var invalidToken: String =
    JwtCirce.encode(JwtClaim("claim"))

  // before {}

  "getKeysUrl" should "return the correct URL given arguments" in {
    CognitoJWTAuthenticator.getKeysUrl("foo", "bar") should equal(
      "https://cognito-idp.foo.amazonaws.com/bar/.well-known/jwks.json"
    )
  }

  "validateToken" should "return true if supplied token is valid" in {
    CognitoJWTAuthenticator.validateToken(testToken) should equal(true)
  }

  "validateToken" should "return false if supplied token is invalid" in {
    CognitoJWTAuthenticator.validateToken(invalidToken) should equal(false)
  }

//  "getUserIdFromToken" should "return user ID from supplied token payload" in {
//    CognitoJWTAuthenticator.getUserIdFromToken(testToken) should equal("userid")
//  }

}
