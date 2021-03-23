// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.uploads

import com.pennsieve.test.helpers.EitherValue._
import java.util.UUID

import com.pennsieve.models.{ FileType, JobId, PackageType }
import org.scalatest.{ FlatSpec, Matchers }

class PackagePreviewSpec extends FlatSpec with Matchers {

  "fromFiles" should "PackagePreviewSpec.scalaname a package correctly when the type is known" in {
    val importId: JobId = new JobId(UUID.randomUUID)

    val preview = PackagePreview
      .fromFiles(
        List(FileUpload(s"test@pennsieve.org/$importId/picture.png")),
        importId.toString
      )
      .value

    preview.packageName shouldBe "picture.png"
    preview.packageType shouldBe PackageType.Image
    preview.fileType shouldBe FileType.PNG
  }

  "fromFiles" should "name a package correctly when the type is unknown" in {
    val importId: JobId = new JobId(UUID.randomUUID)

    val preview = PackagePreview
      .fromFiles(
        List(FileUpload(s"test@pennsieve.org/$importId/thing.xyz")),
        importId.toString
      )
      .value

    preview.packageName shouldBe "thing.xyz"
    preview.packageType shouldBe PackageType.Unknown
    preview.fileType shouldBe FileType.GenericData
  }

}
