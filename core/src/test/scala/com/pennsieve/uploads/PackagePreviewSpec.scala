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

package com.pennsieve.uploads

import org.scalatest.EitherValues._

import java.util.UUID
import com.pennsieve.models.{ FileType, JobId, PackageType }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PackagePreviewSpec extends AnyFlatSpec with Matchers {

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
