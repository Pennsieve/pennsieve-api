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

package com.pennsieve.api

import java.util.UUID
import com.pennsieve.models._
import org.json4s
import org.json4s._
import org.json4s.jackson.Serialization.{ read, write }
import io.circe
import com.pennsieve.helpers.ModelSerializers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TestModelSerializers extends AnyFlatSpec with Matchers {

  protected implicit val jsonFormats
    : Formats = DefaultFormats ++ ModelSerializers.serializers

  // Serialize Circe Json to Json4s AST
  def toJson4s: circe.Json => JValue = Extraction.decompose(_)

  "model serializers" should "serialize UpdateRecord.PropertyDiff event detail" in {
    Extraction.decompose(
      ChangelogEventDetail.PropertyDiff(
        name = "name",
        dataType = Some(
          circe.Json
            .obj(
              ("type", circe.Json.fromString("String")),
              ("format", circe.Json.Null)
            )
        ),
        oldValue = circe.Json.Null,
        newValue = circe.Json.fromString("Alice")
      )
    ) shouldBe JObject(
      "name" -> JString("name"),
      "dataType" -> JObject(("type", JString("String")), ("format", JNull)),
      "oldValue" -> JNull,
      "newValue" -> JString("Alice")
    )
  }

  "model serializers" should "serialize Circe boolean" in {
    toJson4s(circe.Json.True) shouldBe JBool(true)
  }

  "model serializers" should "serialize Circe array" in {
    toJson4s(circe.Json.arr(circe.Json.fromString("test"))) shouldBe JArray(
      List(JString("test"))
    )
  }

  "model serializers" should "serialize Circe object" in {
    toJson4s(circe.Json.obj(("name", circe.Json.fromString("Alice")))) shouldBe JObject(
      "name" -> JString("Alice")
    )
  }

  "model serializers" should "serialize Circe big decimal" in {
    val big = BigDecimal(Double.MaxValue) * 4
    toJson4s(circe.Json.fromBigDecimal(big)) shouldBe JDecimal(big)

    // Should be serialized as decimal
    write(circe.Json.fromBigDecimal(big)) shouldBe big.toString()
  }

  "model serializers" should "serialize Circe big int" in {
    val big = BigInt(Long.MaxValue) * 4
    toJson4s(circe.Json.fromBigInt(big)) shouldBe JDecimal(BigDecimal(big))

    // Should be serialized as an integer
    write(circe.Json.fromBigInt(big)) shouldBe big.toString()
  }

}
