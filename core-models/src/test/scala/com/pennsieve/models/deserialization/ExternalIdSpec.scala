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

import com.pennsieve.models.{ ExternalId, FileManifest, FileType }
import io.circe.parser.decode
import io.circe.syntax._
import io.circe._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues._

class ExternalIdSpec extends AnyWordSpecLike with Matchers {

  "An external ID should be serialized" in {
    ExternalId(Left(1234)).asJson shouldBe Json.fromInt(1234)
    ExternalId(Right("N:package:1234")).asJson shouldBe Json.fromString(
      "N:package:1234"
    )
  }

  "An external ID should be deserialized from string and integer JSON" in {
    decode[ExternalId](""""N:package:1234"""").value shouldBe ExternalId(
      Right("N:package:1234")
    )
    decode[ExternalId]("""1234""").value shouldBe ExternalId(Left(1234))
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

  "A Filemanifest with no specific name decodes the name" in {
    decode[FileManifest]("""{
      "path" : "packages/brain.dcm",
      "size" : 15010,
      "fileType" : "DICOM",
      "sourcePackageId" : "N:package:1"
    }""").value shouldBe FileManifest(
      "brain.dcm",
      "packages/brain.dcm",
      15010,
      FileType.DICOM,
      Some("N:package:1")
    )
  }
  "A Filemanifest with specific name decodes the name" in {
    decode[FileManifest]("""{
      "name" : "testName",
      "path" : "packages/brain.dcm",
      "size" : 15010,
      "fileType" : "DICOM",
      "sourcePackageId" : "N:package:1"
    }""").value shouldBe FileManifest(
      "testName",
      "packages/brain.dcm",
      15010,
      FileType.DICOM,
      Some("N:package:1")
    )
  }

  "A FileManifest with SHA256" in {
    decode[FileManifest]("""{
        "name" : "testName",
        "path" : "packages/brain.dcm",
        "size" : 15010,
        "fileType" : "DICOM",
        "sourcePackageId" : "N:package:1",
        "s3VersionId": "a1b2c3d4",
        "sha256": "xcv43dfl=-11"
    }""").value shouldBe FileManifest(
      "testName",
      "packages/brain.dcm",
      15010,
      FileType.DICOM,
      Some("N:package:1"),
      None,
      Some("a1b2c3d4"),
      Some("xcv43dfl=-11")
    )
  }
}
