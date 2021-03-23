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
