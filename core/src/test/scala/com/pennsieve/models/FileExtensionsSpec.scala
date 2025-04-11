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

import com.pennsieve.core.{ utilities => coreUtilities }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FileExtensionsSpec extends AnyFlatSpec with Matchers {

  "splitFileName" should "return correctly for normal file names" in {

    coreUtilities.splitFileName("picture.png") should equal(("picture", ".png"))
    coreUtilities.splitFileName("matlab.m") should equal(("matlab", ".m"))
    coreUtilities.splitFileName("msaccess.mat") should equal(
      ("msaccess", ".mat")
    )
    coreUtilities.splitFileName("nicolet.e") should equal(("nicolet", ".e"))
    coreUtilities.splitFileName("nifti.nii") should equal(("nifti", ".nii"))
    coreUtilities.splitFileName("annotation.bfannot") should equal(
      ("annotation", ".bfannot")
    )

  }

  "splitFileName" should "return correctly for file names with complex extensions" in {

    coreUtilities.splitFileName("reading.moberg.gz") should equal(
      ("reading", ".moberg.gz")
    )
    coreUtilities.splitFileName("ts.mefd.gz") should equal(("ts", ".mefd.gz"))
    coreUtilities.splitFileName("nifti.nii.gz") should equal(
      ("nifti", ".nii.gz")
    )

  }

  "splitFileName" should "return the longest matching extension" in {

    coreUtilities.splitFileName("image.ome.tiff") should equal(
      ("image", ".ome.tiff")
    )

  }

  "splitFileName" should "return an empty string extension when it doesn't match a supported extension" in {

    coreUtilities.splitFileName("bad.blah") should equal(("bad.blah", ""))
    coreUtilities.splitFileName("bad.blah.gz") should equal(("bad.blah.gz", ""))

  }

  "splitFileName" should "handles file names that include S3 directory paths appropriately" in {

    coreUtilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/simple.csv"
    ) should equal(("simple", ".csv"))
    coreUtilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/reading.moberg.gz"
    ) should equal(("reading", ".moberg.gz"))
    coreUtilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/nifti.nii.gz"
    ) should equal(("nifti", ".nii.gz"))
    coreUtilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/bad.blah"
    ) should equal(("bad.blah", ""))

  }

  "FileTypeInfo.get" should "return correct data for BDF files" in {
    val info = FileTypeInfo.get(FileType.BDF)
    info.fileType shouldBe FileType.BDF
    info.packageType shouldBe PackageType.TimeSeries
    info.packageSubtype shouldBe "Timeseries"
    info.icon shouldBe Icon.Timeseries
  }

}
