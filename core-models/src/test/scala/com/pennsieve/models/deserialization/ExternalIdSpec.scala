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

package com.pennsieve.models.deserialization

import com.pennsieve.models.ExternalId

import org.scalatest.{ Matchers, WordSpecLike }
import io.circe.parser.decode
import io.circe.syntax._
import io.circe._

class ExternalIdSpec extends WordSpecLike with Matchers {

  "An external ID should be serialized" in {
    ExternalId(Left(1234)).asJson shouldBe Json.fromInt(1234)
    ExternalId(Right("N:package:1234")).asJson shouldBe Json.fromString(
      "N:package:1234"
    )
  }

  "An external ID should be deserialized from string and integer JSON" in {
    decode[ExternalId](""""N:package:1234"""").right.get shouldBe ExternalId(
      Right("N:package:1234")
    )
    decode[ExternalId]("""1234""").right.get shouldBe ExternalId(Left(1234))
  }

  "An external ID should fail to deserialized from non-string and non-integer values" in {
    decode[ExternalId]("""null""").isLeft shouldBe true
    decode[ExternalId]("""{}""").isLeft shouldBe true
    decode[ExternalId]("""[23]""").isLeft shouldBe true
    decode[ExternalId]("""1234.01""").isLeft shouldBe true
  }

  "A Map of external IDs should be serialized" in {
    Map(
      ExternalId.intId(12) -> "value1",
      ExternalId.nodeId("N:package:1234") -> "value2"
    ).asJson.noSpaces shouldBe """{"12":"value1","N:package:1234":"value2"}"""
  }

  "A Map of external IDs should be deserialized" in {
    decode[Map[ExternalId, String]](
      """{"12":"value1","N:package:1234":"value2"}"""
    ) shouldBe Right(
      Map(
        ExternalId.intId(12) -> "value1",
        ExternalId.nodeId("N:package:1234") -> "value2"
      )
    )
  }
}
