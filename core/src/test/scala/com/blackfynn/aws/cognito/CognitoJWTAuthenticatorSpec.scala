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

import com.pennsieve.models.CognitoId
import org.scalatest.{ BeforeAndAfter, FlatSpec, Matchers }
import pdi.jwt.{ JwtCirce, JwtClaim, JwtHeader }

import java.util.UUID
import java.time.Instant

class CognitoJWTAuthenticatorSpec
    extends FlatSpec
    with Matchers
    with BeforeAndAfter {

  var testToken: String = JwtCirce.encode(
    header = "{\"kid\": \"keyId\"}",
    claim = "{\"sub\": \"0f14d0ab-9605-4a62-a9e4-5ed26688389b\", \"iat\": " + (Instant
      .now()
      .toEpochMilli() / 1000 + 9999)
      + "}"
  )
//  var invalidToken: String = JwtCirce.encode
//    JwtCirce.encode(JwtClaim("claim"))

  var testPublicKeysJson: String =
    "{\n\t\"keys\": [{\n\t\t\"kid\": \"1234example=\",\n\t\t\"alg\": \"RS256\",\n\t\t\"kty\": \"RSA\",\n\t\t\"e\": \"AQAB\",\n\t\t\"n\": \"1234567890\",\n\t\t\"use\": \"sig\"\n\t}, {\n\t\t\"kid\": \"5678example=\",\n\t\t\"alg\": \"RS256\",\n\t\t\"kty\": \"RSA\",\n\t\t\"e\": \"AQAB\",\n\t\t\"n\": \"987654321\",\n\t\t\"use\": \"sig\"\n\t}]\n}"

  // before {}

  "getKeysUrl" should "return the correct URL given arguments" in {
    CognitoJWTAuthenticator.getKeysUrl("foo", "bar") should equal(
      "https://cognito-idp.foo.amazonaws.com/bar/.well-known/jwks.json"
    )
  }

  "getKeyId" should "work" in {
    CognitoJWTAuthenticator.getKeyId(testToken) should equal(Right("keyId"))
  }

  "validateJwt" should "return CognitoPayload if supplied token is valid" in {
    CognitoJWTAuthenticator.validateJwt(
      "foo",
      "bar",
      "appuserfoo,appuserbar",
      testToken,
      CognitoJWTAuthenticator.getJwkProvider("foo", "bar")
    ) should equal(
      new CognitoPayload(
        CognitoId(UUID.fromString("0f14d0ab-9605-4a62-a9e4-5ed26688389b")),
        java.time.Instant.now()
      )
    )
  }

}
