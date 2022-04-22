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

package com.pennsieve.models.utilities

import com.pennsieve.models.{
  File,
  FileObjectType,
  FileProcessingState,
  FileType,
  Utilities
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TestUtilities extends AnyWordSpecLike with Matchers {

  "safeS3Key" should {
    "remove bad characters" in {
      Utilities.cleanS3Key("++file") shouldBe "__file"
      Utilities.cleanS3Key("my/file") shouldBe "my_file"
      Utilities.cleanS3Key("my~file") shouldBe "my_file"
      Utilities.cleanS3Key("many     spaces") shouldBe "many_____spaces"
      Utilities.cleanS3Key("file+1") shouldBe "file_1"
      Utilities.cleanS3Key("file, 1") shouldBe "file__1"
      Utilities.cleanS3Key("file: 1") shouldBe "file__1"
      Utilities.cleanS3Key("file; 1") shouldBe "file__1"
    }

//    "names should be trimmed in" in {
//      Utilities.cleanS3Key(" hello ") shouldBe "hello_"
//    }

//    "preserve spaces in escaped keys" in {
//      Utilities.cleanS3Key("My file") shouldBe "My file"
//    }

    "preserve allowed S3 characters" in {
      Utilities.cleanS3Key("My (file)") shouldBe "My_(file)"
      Utilities.cleanS3Key("file!") shouldBe "file!"
      Utilities.cleanS3Key("fi-le") shouldBe "fi-le"
      Utilities.cleanS3Key("fi_le") shouldBe "fi_le"
      Utilities.cleanS3Key("file.zip") shouldBe "file.zip"
      Utilities.cleanS3Key("'file'") shouldBe "'file'"
      Utilities.cleanS3Key("file* 1") shouldBe "file*_1"
      Utilities.cleanS3Key("()*_-'.!") shouldBe "()*_-'.!"
      Utilities.cleanS3Key("Å") shouldBe "Å" //unicode test
    }

    "replace UNIX path special characters" in {
      Utilities.cleanS3Key(".") shouldBe "%2E"
      Utilities.cleanS3Key("..") shouldBe "%2E%2E"
    }

    "be idempotent" in {
      assert(
        Utilities.cleanS3Key("my+file") == Utilities
          .cleanS3Key(Utilities.cleanS3Key("my+file"))
      )
      val crazyFileName =
        "@ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo @ ! _ %20foo .png"
      Utilities.cleanS3Key(crazyFileName) shouldBe Utilities.cleanS3Key(
        Utilities.cleanS3Key(crazyFileName)
      )
    }

    "trim long length" in {
      Utilities.cleanS3Key("1234567890" * 13) shouldBe "1234567890" * 12 + "12345678"
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
