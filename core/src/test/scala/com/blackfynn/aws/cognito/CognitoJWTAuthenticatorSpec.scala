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
import io.circe.generic.decoding.DerivedDecoder.deriveDecoder
import org.scalatest.{ FlatSpec, Matchers }
import pdi.jwt.{ JwtAlgorithm, JwtCirce }

import java.util.UUID
import java.time.Instant
import scala.collection.JavaConverters.mapAsJavaMap

class MockJwkProvider(jwk: Jwk) extends JwkProvider {
  def get(keyId: String): Jwk = {
    jwk
  }
}

class CognitoJWTAuthenticatorSpec extends FlatSpec with Matchers {

  case class CognitoPublicKey(
    kid: String,
    alg: String,
    kty: String,
    e: String,
    n: String,
    use: String
  ) {
    def toMap: Map[String, Object] = Map(
      "kid" -> kid,
      "alg" -> alg,
      "kty" -> kty,
      "e" -> e,
      "n" -> n,
      "use" -> use
    )
  }

  var testPublicKeyJson: String =
    "{\n  \"kty\" : \"RSA\",\n \"alg\" : \"RS256\", \"kid\" : \"cc34c0a0-bd5a-4a3c-a50d-a2a7db7643df\",\n  \"use\" " +
      ": \"sig\",\n  \"n\"   " +
      ": \"pjdss8ZaDfEH6K6U7GeW2nxDqR4IP049fk1fK0lndimbMMVBdPv_hSpm8T8EtBDxrUdi1OHZfMhUixGaut-3nQ4GG9nM249oxhCtxqqNvEXrmQRGqczyLxuh-fKn9Fg--hS9UpazHpfVAFnB5aCfXoNhPuI8oByyFKMKaOVgHNqP5NBEqabiLftZD3W_lsFCPGuzr4Vp0YS7zS2hDYScC2oOMu4rGU1LcMZf39p3153Cq7bS2Xh6Y-vw5pwzFYZdjQxDn8x8BG3fJ6j8TGLXQsbKH1218_HcUJRvMwdpbUQG5nvA2GXVqLqdwp054Lzk9_B_f1lVrmOKuHjTNHq48w\",\n  \"e\"   : \"AQAB\",\n  \"d\"   : \"ksDmucdMJXkFGZxiomNHnroOZxe8AmDLDGO1vhs-POa5PZM7mtUPonxwjVmthmpbZzla-kg55OFfO7YcXhg-Hm2OWTKwm73_rLh3JavaHjvBqsVKuorX3V3RYkSro6HyYIzFJ1Ek7sLxbjDRcDOj4ievSX0oN9l-JZhaDYlPlci5uJsoqro_YrE0PRRWVhtGynd-_aWgQv1YzkfZuMD-hJtDi1Im2humOWxA4eZrFs9eG-whXcOvaSwO4sSGbS99ecQZHM2TcdXeAs1PvjVgQ_dKnZlGN3lTWoWfQP55Z7Tgt8Nf1q4ZAKd-NlMe-7iqCFfsnFwXjSiaOa2CRGZn-Q\",\n  \"p\"   : \"4A5nU4ahEww7B65yuzmGeCUUi8ikWzv1C81pSyUKvKzu8CX41hp9J6oRaLGesKImYiuVQK47FhZ--wwfpRwHvSxtNU9qXb8ewo-BvadyO1eVrIk4tNV543QlSe7pQAoJGkxCia5rfznAE3InKF4JvIlchyqs0RQ8wx7lULqwnn0\",\n  \"q\"   : \"ven83GM6SfrmO-TBHbjTk6JhP_3CMsIvmSdo4KrbQNvp4vHO3w1_0zJ3URkmkYGhz2tgPlfd7v1l2I6QkIh4Bumdj6FyFZEBpxjE4MpfdNVcNINvVj87cLyTRmIcaGxmfylY7QErP8GFA-k4UoH_eQmGKGK44TRzYj5hZYGWIC8\",\n  \"dp\"  : \"lmmU_AG5SGxBhJqb8wxfNXDPJjf__i92BgJT2Vp4pskBbr5PGoyV0HbfUQVMnw977RONEurkR6O6gxZUeCclGt4kQlGZ-m0_XSWx13v9t9DIbheAtgVJ2mQyVDvK4m7aRYlEceFh0PsX8vYDS5o1txgPwb3oXkPTtrmbAGMUBpE\",\n  \"dq\"  : \"mxRTU3QDyR2EnCv0Nl0TCF90oliJGAHR9HJmBe__EjuCBbwHfcT8OG3hWOv8vpzokQPRl5cQt3NckzX3fs6xlJN4Ai2Hh2zduKFVQ2p-AF2p6Yfahscjtq-GY9cB85NxLy2IXCC0PF--Sq9LOrTE9QV988SJy_yUrAjcZ5MmECk\",\n  \"qi\"  : \"ldHXIrEmMZVaNwGzDF9WG8sHj2mOZmQpw9yrjLK9hAsmsNr5LTyqWAqJIYZSwPTYWhY4nu2O0EY9G9uYiqewXfCKw_UngrJt8Xwfq1Zruz0YY869zPN4GiE9-9rzdZB33RBw8kIOquY3MK74FMwCihYx_LiU2YTHkaoJ3ncvtvg\"\n}"

  var jsonMap: Either[io.circe.Error, CognitoPublicKey] =
    io.circe.parser.decode[CognitoPublicKey](testPublicKeyJson)

  var jwk: Jwk = Jwk.fromValues(mapAsJavaMap(jsonMap.right.get.toMap))

  var testToken: String = JwtCirce.encode(
    header = "{\"kid\": \"cc34c0a0-bd5a-4a3c-a50d-a2a7db7643df\"}",
    claim = "{\"sub\": \"0f14d0ab-9605-4a62-a9e4-5ed26688389b\", \"iat\": " + (Instant
      .now()
      .toEpochMilli() / 1000 + 9999)
      + "}",
    key = jwk.getPublicKey.toString,
    algorithm = JwtAlgorithm.RS256
  )

  //  var invalidToken: String = JwtCirce.encode

  var jwkProvider: JwkProvider = new MockJwkProvider(jwk)

  "getKeyId" should "work" in {
    CognitoJWTAuthenticator.getKeyId(testToken) should equal(
      Right("cc34c0a0-bd5a-4a3c-a50d-a2a7db7643df")
    )
  }

  /*
 * TODO: Come back and finish this test fingers crossed as well as tests for
 * invalid JWTs

  "validateJwt" should "return CognitoPayload if supplied token is valid" in {
    CognitoJWTAuthenticator.validateJwt(
      "foo",
      "bar",
      "appuserfoo,appuserbar",
      testToken,
      jwkProvider
    ) should equal(
      new CognitoPayload(
        CognitoId(UUID.fromString("0f14d0ab-9605-4a62-a9e4-5ed26688389b")),
        java.time.Instant.now()
      )
    )
  }
 */

}
