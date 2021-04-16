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
import io.circe.Decoder
import io.circe.generic.decoding.DerivedDecoder.deriveDecoder
import org.scalatest.{ FlatSpec, Matchers }
import pdi.jwt.{ JwtAlgorithm, JwtCirce }
import software.amazon.awssdk.regions.Region
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.JWK
import com.pennsieve.models.CognitoId

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID
import java.security.interfaces.{ RSAPrivateKey, RSAPublicKey }
import java.time.Instant
import scala.collection.JavaConverters._

class MockJwkProvider() extends JwkProvider {

  // JwkProvider interface

  override def get(keyId: String): Jwk =
    generatedJwk

  // Implementation details

  def jwkKeyId: String = "9bed6ab5-3c35-498b-8802-6992333f889c"
  def publicKey: RSAPublicKey = keyPair.getPublic.asInstanceOf[RSAPublicKey]
  def privateKey: RSAPrivateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]

  private lazy val keyPair: KeyPair = {
    val gen: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair
  }

  /**
    * Generate a JWK from this private key pair
    */
  private lazy val generatedJwk: Jwk = {
    val nimbusJwk: JWK =
      new RSAKey.Builder(publicKey)
        .privateKey(privateKey)
        .keyUse(KeyUse.SIGNATURE)
        .keyID(jwkKeyId)
        .build

    val jwkValues =
      io.circe.parser
        .decode[Map[String, String]](nimbusJwk.toPublicJWK.toJSONString)
        .right
        .get
        .asJava
        .asInstanceOf[java.util.Map[String, Object]]

    Jwk.fromValues(jwkValues)
  }

  def generateCognitoToken(
    cognitoId: CognitoId,
    cognitoPool: CognitoPoolConfig,
    issuedAt: Instant,
    validUntil: Instant
  ) = JwtCirce.encode(
    header = s"""{"kid": "$jwkKeyId", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$cognitoId",
        "iss": "${cognitoPool.endpoint}",
        "iat": ${secondsSinceEpoch(issuedAt)},
        "exp": ${secondsSinceEpoch(validUntil)},
        "aud": "${cognitoPool.appClientId}",
        "cognito:username": "$cognitoId"
      }
    """,
    key = privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  private def secondsSinceEpoch(i: Instant): Long =
    i.toEpochMilli / 1000

  def generateValidCognitoToken(
    cognitoId: CognitoId,
    cognitoPool: CognitoPoolConfig
  ) = generateCognitoToken(
    cognitoId,
    cognitoPool,
    issuedAt = Instant.now().minusSeconds(60),
    validUntil = Instant.now().plusSeconds(60 * 60)
  )
}

class CognitoJWTAuthenticatorSpec extends FlatSpec with Matchers {

  val pennsieveUserId: String = "0f14d0ab-9605-4a62-a9e4-5ed26688389b"
  val cognitoPoolId: String = "12345"
  val cognitoAppClientId: String = "67890"
  val cognitoPoolId2: String = "abcdef"
  val cognitoAppClientId2: String = "ghijkl"

  val jwkProvider = new MockJwkProvider()

  implicit val cConfig = CognitoConfig(
    Region.AP_SOUTH_1,
    CognitoPoolConfig(
      Region.AP_SOUTH_1,
      cognitoPoolId,
      cognitoAppClientId,
      _ => jwkProvider
    ),
    CognitoPoolConfig(
      Region.AP_SOUTH_1,
      cognitoPoolId2,
      cognitoAppClientId2,
      _ => jwkProvider
    )
  )

  val issuedAtTime: Long = Instant.now().toEpochMilli() / 1000 - 90
  val validTokenTime: Long = Instant.now().toEpochMilli() / 1000 + 9999

  val validToken: String = JwtCirce.encode(
    header = s"""{"kid": "${jwkProvider.jwkKeyId}", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$pennsieveUserId",
        "iss": "https://cognito-idp.${Region.AP_SOUTH_1}.amazonaws.com/$cognitoPoolId",
        "iat": $issuedAtTime,
        "exp": $validTokenTime,
        "aud": "$cognitoAppClientId",
        "cognito:username": "$pennsieveUserId"
      }
    """,
    key = jwkProvider.privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  "getKeyId" should "return the correct jwk key id from the token" in {
    CognitoJWTAuthenticator.getKeyId(validToken) should equal(
      Right(jwkProvider.jwkKeyId)
    )
  }

  "validateJwt" should "return CognitoPayload w/ correct data if supplied token is valid" in {
    val tokenValidatorResponse =
      CognitoJWTAuthenticator.validateJwt(validToken)

    tokenValidatorResponse.isRight should be(true)
    tokenValidatorResponse.right.get.id.toString should be(
      UUID.fromString(pennsieveUserId).toString
    )
    tokenValidatorResponse.right.get.expiresAt should be(
      Instant.ofEpochSecond(validTokenTime)
    )
  }

  val invalidTokenTime: Long = Instant.now().toEpochMilli() / 1000 - 9999999

  val invalidToken_Expired: String = JwtCirce.encode(
    header = s"""{"kid": "${jwkProvider.jwkKeyId}", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$pennsieveUserId",
        "iss": "https://cognito-idp.${Region.AP_SOUTH_1}.amazonaws.com/$cognitoPoolId",
        "iat": $issuedAtTime,
        "exp": $invalidTokenTime,
        "aud": "$cognitoAppClientId",
        "cognito:username": "$pennsieveUserId"
      }
    """,
    key = jwkProvider.privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  "validateJWT" should "return false / error if the token passed in has expired" in {
    CognitoJWTAuthenticator
      .validateJwt(invalidToken_Expired)
      .isRight should be(false)
  }

  val invalidToken_Audience: String = JwtCirce.encode(
    header = s"""{"kid": "${jwkProvider.jwkKeyId}", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$pennsieveUserId",
        "iss": "https://cognito-idp.${Region.AP_SOUTH_1}.amazonaws.com/$cognitoPoolId",
        "iat": $issuedAtTime,
        "exp": $validTokenTime,
        "aud": "notAnAudience",
        "cognito:username": "$pennsieveUserId"
      }
    """,
    key = jwkProvider.privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  "validateJWT" should "return false / error if the audience is invalid" in {
    CognitoJWTAuthenticator
      .validateJwt(invalidToken_Audience)
      .isRight should be(false)
  }

  val invalidToken_Issuer: String = JwtCirce.encode(
    header = s"""{"kid": "${jwkProvider.jwkKeyId}", "alg": "RS256"}""",
    claim = s"""
      {
        "sub": "$pennsieveUserId",
        "iss": "https://cognito-idp.${Region.AP_SOUTHEAST_2}.amazonaws.com/$cognitoPoolId",
        "iat": $issuedAtTime,
        "exp": $validTokenTime,
        "aud": "$cognitoAppClientId",
        "cognito:username": "$pennsieveUserId"
      }
    """,
    key = jwkProvider.privateKey,
    algorithm = JwtAlgorithm.RS256
  )

  "validateJWT" should "return false / error if the issuer is invalid" in {
    CognitoJWTAuthenticator
      .validateJwt(invalidToken_Issuer)
      .isLeft should be(true)
  }

}
