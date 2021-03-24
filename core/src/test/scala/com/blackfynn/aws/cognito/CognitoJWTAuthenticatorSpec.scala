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

package com.blackfynn.aws.cognito

import org.scalatest.{ BeforeAndAfter, FlatSpec, Matchers }
import pdi.jwt.{ JwtCirce, JwtClaim }

class CognitoJWTAuthenticatorSpec
    extends FlatSpec
    with Matchers
    with BeforeAndAfter {

  var testToken: String =
    JwtCirce.encode(JwtClaim("claim"))
  var invalidToken: String =
    JwtCirce.encode(JwtClaim("claim"))

  var testPublicKeysJson: String =
    "{\n\t\"keys\": [{\n\t\t\"kid\": \"1234example=\",\n\t\t\"alg\": \"RS256\",\n\t\t\"kty\": \"RSA\",\n\t\t\"e\": \"AQAB\",\n\t\t\"n\": \"1234567890\",\n\t\t\"use\": \"sig\"\n\t}, {\n\t\t\"kid\": \"5678example=\",\n\t\t\"alg\": \"RS256\",\n\t\t\"kty\": \"RSA\",\n\t\t\"e\": \"AQAB\",\n\t\t\"n\": \"987654321\",\n\t\t\"use\": \"sig\"\n\t}]\n}"

  // before {}

  "getKeysUrl" should "return the correct URL given arguments" in {
    CognitoJWTAuthenticator.getKeysUrl("foo", "bar") should equal(
      "https://cognito-idp.foo.amazonaws.com/bar/.well-known/jwks.json"
    )
  }


  "validateToken" should "return true if supplied token is valid" in {
    CognitoJWTAuthenticator.validateToken(testToken) should equal(true)
  }

//  "getUserIdFromToken" should "return user ID from supplied token payload" in {
//    CognitoJWTAuthenticator.getUserIdFromToken(testToken) should equal("userid")
//  }

}
