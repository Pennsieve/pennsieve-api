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

package com.pennsieve.aws.cognito

import com.auth0.jwk.{ Jwk, JwkProvider }
import com.pennsieve.models.CognitoId
import io.circe.Decoder
import io.circe.generic.decoding.DerivedDecoder.deriveDecoder
import org.scalatest.{ FlatSpec, Matchers }
import pdi.jwt.{ JwtAlgorithm, JwtCirce }
import software.amazon.awssdk.regions.Region
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey

import com.nimbusds.jose.jwk.JWK
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID
import java.security.interfaces.{ RSAPrivateKey, RSAPublicKey }
import java.time.Instant
import scala.collection.JavaConverters.mapAsJavaMap

class MockJwkProvider(jwk: Jwk) extends JwkProvider {
  def get(keyId: String): Jwk = {
    jwk
  }
}

case class CognitoPublicKey(
  p: String,
  kty: String,
  q: String,
  d: String,
  e: String,
  use: String,
  kid: String,
  qi: String,
  dp: String,
  dq: String,
  n: String
) extends Product {
  def toMap: Map[String, String] =
    Map(
      "p" -> p,
      "kty" -> kty,
      "q" -> q,
      "d" -> d,
      "e" -> e,
      "use" -> use,
      "kid" -> kid,
      "qi" -> qi,
      "dp" -> dp,
      "dq" -> dq,
      "n" -> n
    )
}

object CognitoPublicKey {
  implicit val decoder: Decoder[CognitoPublicKey] = deriveDecoder
}

class CognitoJWTAuthenticatorSpec extends FlatSpec with Matchers {

  var keyId: String = "9bed6ab5-3c35-498b-8802-6992333f889c"
  var userId: String = "0f14d0ab-9605-4a62-a9e4-5ed26688389b"

  val gen: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  gen.initialize(2048)
  val keyPair: KeyPair = gen.generateKeyPair

  val nimbusJwk: JWK =
    new RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey])
      .privateKey(keyPair.getPrivate.asInstanceOf[RSAPrivateKey])
      .keyUse(KeyUse.SIGNATURE)
      .keyID(userId)
      .build

  var jsonMap: Either[io.circe.Error, CognitoPublicKey] =
    io.circe.parser.decode[CognitoPublicKey](nimbusJwk.toJSONString)

  var jwk2: Jwk = Jwk.fromValues(mapAsJavaMap(jsonMap.right.get.toMap))

  var tokenTime: Long = Instant.now().toEpochMilli() / 1000 + 9999
  var tokenHeader: String = s"""{"kid": "$keyId", "alg": "RS256"}"""
  var tokenClaim: String = s"""
    {
      "sub": "$userId",
      "iss": "https://cognito-idp.${Region.AP_SOUTH_1}.amazonaws.com/12345",
      "iat": ${tokenTime},
      "aud": "12345",
      "cognito:username": "$userId"
    }
  """

  var testToken: String = JwtCirce.encode(
    header = tokenHeader,
    claim = tokenClaim,
    key = keyPair.getPrivate.asInstanceOf[RSAPrivateKey],
    algorithm = JwtAlgorithm.RS256
  )

  var jwkProvider: JwkProvider = new MockJwkProvider(jwk2)

  "getKeyId" should "return the correct jwk key id from the token" in {
    CognitoJWTAuthenticator.getKeyId(testToken) should equal(Right(keyId))
  }

  var cConfig = CognitoConfig(
    Region.AP_SOUTH_1,
    CognitoPoolConfig(Region.AP_SOUTH_1, "12345", "12345"),
    CognitoPoolConfig(Region.AP_SOUTH_1, "12345", "12345"),
    jwkProvider
  )

  "validateJwt" should "return CognitoPayload if supplied token is valid" in {
    CognitoJWTAuthenticator.validateJwt(testToken)(cConfig) should equal(
      Right(
        new CognitoPayload(
          CognitoId(UUID.fromString("0f14d0ab-9605-4a62-a9e4-5ed26688389b")),
          Instant.ofEpochSecond(tokenTime)
        )
      )
    )
  }
}
