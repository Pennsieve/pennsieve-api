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

import com.pennsieve.models.File
import com.pennsieve.models.FileType

import org.scalatest.{ Matchers, WordSpecLike }
import io.circe.parser.decode

class FileSpec extends WordSpecLike with Matchers {

  "A file object from the database should be deserialized" in {
    val fileDbJson =
      """
        |{
        |   "id":1,
        |   "package_id":1,
        |   "name":"a,sd,g,h,",
        |   "file_type":"GenericData",
        |   "s3_bucket":"bucket/xRTuIQsqHo",
        |   "s3_key":"key/jwwuJFmUhj",
        |   "object_type":"source",
        |   "processing_state":"processed",
        |   "created_at":"2019-03-13T10:11:59.375272",
        |   "updated_at":"2019-03-13T10:11:59.375272",
        |   "size":0,
        |   "checksum":null,
        |   "row_num":1,
        |   "uuid":"7c22ada0-fb54-4e1f-9ba1-2ccdf9815fc0"
        |}
      """.stripMargin

    val result = decode[File](fileDbJson)

    result.isRight shouldBe true
  }

  "filetype FACS is translated to FCS" in {
    val file1 = FileType.withName("FACS")
    val file2 = FileType.withName("FCS")

    file1 shouldBe FileType.FCS
    file2 shouldBe FileType.FCS
  }
}
