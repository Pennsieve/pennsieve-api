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

package com.pennsieve.models

import org.scalatest.{ Matchers, WordSpecLike }
import io.circe.parser.decode

class TestUtilities extends WordSpecLike with Matchers {

  "escapeName" should {
    "preserve spaces in escaped keys" in {
      Utilities.escapeName("My file") shouldBe "My file"
    }

    "remove bad characters" in {
      Utilities.escapeName("my/file") shouldBe "my%2Ffile"
      Utilities.escapeName("my~file") shouldBe "my%7Efile"
      Utilities.escapeName("many     spaces") shouldBe "many spaces"
      Utilities.escapeName("file+1") shouldBe "file%2B1"
      Utilities.escapeName("file, 1") shouldBe "file%2C 1"
      Utilities.escapeName("file: 1") shouldBe "file%3A 1"
      Utilities.escapeName("file; 1") shouldBe "file%3B 1"
      Utilities.escapeName("@#$%^&+={}|[]:;<>?/\\,") shouldBe "%40%23%24%25%5E%26%2B%3D%7B%7D%7C%5B%5D%3A%3B%3C%3E%3F%2F%5C%2C"

    }

    "Unicode should work" in {
      Utilities.escapeName("᚛ᚄᚓᚐᚋᚒᚄ ᚑᚄ") shouldBe "᚛ᚄᚓᚐᚋᚒᚄ ᚑᚄ"
      Utilities.escapeName("᚛ᚄᚓᚐᚋᚒᚄ ᚑᚄ ~") shouldBe "᚛ᚄᚓᚐᚋᚒᚄ ᚑᚄ %7E"
    }

    "preserve allowed S3 characters" in {
      Utilities.escapeName("My (file)") shouldBe "My (file)"
      Utilities.escapeName("file!") shouldBe "file!"
      Utilities.escapeName("fi-le") shouldBe "fi-le"
      Utilities.escapeName("fi_le") shouldBe "fi_le"
      Utilities.escapeName("file.zip") shouldBe "file.zip"
      Utilities.escapeName("'file'") shouldBe "'file'"
      Utilities.escapeName("file* 1") shouldBe "file* 1"
      Utilities.escapeName("() *_-'.!") shouldBe "() *_-'.!"
      Utilities.escapeName("Å") shouldBe "Å" //unicode test
      Utilities.escapeName("%20+ %20+ %20+ %20+") shouldBe "%2520%2B %2520%2B %2520%2B %2520%2B"
    }

    "replace UNIX path special characters" in {
      Utilities.escapeName(".") shouldBe "%2E"
      Utilities.escapeName("..") shouldBe "%2E%2E"
    }

    "change invalid special characters into valid characters" in {
      assert(
        Utilities
          .isNameValid(Utilities.escapeName("@#$%^&+={}|[]:;<>?/\\,")) == true
      )
    }

    "be idempotent" in {
      assert(
        Utilities.escapeName("my+file") == Utilities
          .escapeName(Utilities.escapeName("my+file"))
      )
      val crazyFileName =
        "@ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo .png"
      Utilities.escapeName(crazyFileName) shouldBe Utilities.escapeName(
        Utilities.escapeName(crazyFileName)
      )

    }
  }

  "isNameValid" should {
    "be true for all valid special characters" in {
      assert(
        Utilities
          .isNameValid(("( -_*.!)")) == true
      )
    }
  }

  "getPennsieveExtension" should {
    "return the proper extension" in {
      val f = File(
        packageId = 1,
        name = "test.ome.btf",
        fileType = FileType.OMETIFF,
        s3Bucket = "test-bucket",
        s3Key = "test-key/test.ome.btf",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 12
      )

      Utilities.getPennsieveExtension(f.s3Key) shouldBe (".ome.btf")
      f.fileExtension shouldBe Some("ome.btf")
      Utilities.getFullExtension(f.s3Key) shouldBe Some("ome.btf")

    }

    "return empty string when there is no extension" in {
      val f = File(
        packageId = 1,
        name = "test",
        fileType = FileType.GenericData,
        s3Bucket = "test-bucket",
        s3Key = "test-key/test",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 12
      )

      Utilities.getPennsieveExtension(f.s3Key) shouldBe ("")
      f.fileExtension shouldBe None
      Utilities.getFullExtension(f.s3Key) shouldBe None

    }

    "return no extension when there is no known now" in {
      val f = File(
        packageId = 1,
        name = "test.pip.gz",
        fileType = FileType.GenericData,
        s3Bucket = "test-bucket",
        s3Key = "test-key/test.piz",
        objectType = FileObjectType.Source,
        processingState = FileProcessingState.Unprocessed,
        size = 12
      )
      Utilities.getPennsieveExtension(f.s3Key) shouldBe ("")
      f.fileExtension shouldBe Some("piz")
      Utilities.getFullExtension(f.s3Key) shouldBe Some("piz")

    }
  }
}
