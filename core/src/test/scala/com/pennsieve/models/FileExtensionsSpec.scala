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

import com.pennsieve.core.utilities
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FileExtensionsSpec extends AnyFlatSpec with Matchers {

  "splitFileName" should "return correctly for normal file names" in {

    utilities.splitFileName("picture.png") should equal(("picture", ".png"))
    utilities.splitFileName("matlab.m") should equal(("matlab", ".m"))
    utilities.splitFileName("msaccess.mat") should equal(("msaccess", ".mat"))
    utilities.splitFileName("nicolet.e") should equal(("nicolet", ".e"))
    utilities.splitFileName("nifti.nii") should equal(("nifti", ".nii"))
    utilities.splitFileName("annotation.bfannot") should equal(
      ("annotation", ".bfannot")
    )

  }

  "splitFileName" should "return correctly for file names with complex extensions" in {

    utilities.splitFileName("reading.moberg.gz") should equal(
      ("reading", ".moberg.gz")
    )
    utilities.splitFileName("ts.mefd.gz") should equal(("ts", ".mefd.gz"))
    utilities.splitFileName("nifti.nii.gz") should equal(("nifti", ".nii.gz"))

  }

  "splitFileName" should "return the longest matching extension" in {

    utilities.splitFileName("image.ome.tiff") should equal(
      ("image", ".ome.tiff")
    )

  }

  "splitFileName" should "return an empty string extension when it doesn't match a supported extension" in {

    utilities.splitFileName("bad.blah") should equal(("bad.blah", ""))
    utilities.splitFileName("bad.blah.gz") should equal(("bad.blah.gz", ""))

  }

  "splitFileName" should "handles file names that include S3 directory paths appropriately" in {

    utilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/simple.csv"
    ) should equal(("simple", ".csv"))
    utilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/reading.moberg.gz"
    ) should equal(("reading", ".moberg.gz"))
    utilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/nifti.nii.gz"
    ) should equal(("nifti", ".nii.gz"))
    utilities.splitFileName(
      "test@pennsieve.org/545d1f2a-1b0b-4888-91fe-af221e1d55aa/bad.blah"
    ) should equal(("bad.blah", ""))

  }

}
