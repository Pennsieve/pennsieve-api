// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.uploads

import com.blackfynn.models.{ FileType, PackageType }
import org.scalatest.{ FlatSpec, Matchers }

class FileUploadSpec extends FlatSpec with Matchers {

  "constructor" should "correctly handle normal file names" in {

    val fileOne = FileUpload("picture.png")
    fileOne.fileType shouldBe FileType.PNG
    fileOne.info.packageType shouldBe PackageType.Image
    fileOne.isMasterFile shouldBe false

    val fileThree = FileUpload("blackrock.ns2")
    fileThree.fileType shouldBe FileType.NEV
    fileThree.info.packageType shouldBe PackageType.TimeSeries
    fileThree.isMasterFile shouldBe false

    val fileFour = FileUpload("nicolet.e")
    fileFour.fileType shouldBe FileType.Nicolet
    fileFour.info.packageType shouldBe PackageType.TimeSeries
    fileFour.isMasterFile shouldBe false

    val fileFive = FileUpload("nifti.nii")
    fileFive.fileType shouldBe FileType.NIFTI
    fileFive.info.packageType shouldBe PackageType.MRI
    fileFive.isMasterFile shouldBe false

    val fileSix = FileUpload("annotation.bfannot")
    fileSix.fileType shouldBe FileType.BFANNOT
    fileSix.info.packageType shouldBe PackageType.Unknown
    fileSix.isMasterFile shouldBe false

    val fileSeven = FileUpload("picture.PNG")
    fileSeven.fileType shouldBe FileType.PNG
    fileSeven.info.packageType shouldBe PackageType.Image
    fileSeven.isMasterFile shouldBe false
  }

  "constructor" should "correctly handle file names with master extensions" in {

    val fileTwo = FileUpload("reading.lay")
    fileTwo.fileType shouldBe FileType.Persyst
    fileTwo.info.packageType shouldBe PackageType.TimeSeries
    fileTwo.isMasterFile shouldBe true

    val fileThree = FileUpload("scan.img")
    fileThree.fileType shouldBe FileType.ANALYZE
    fileThree.info.packageType shouldBe PackageType.MRI
    fileThree.isMasterFile shouldBe true

  }

  "constructor" should "handle unsupported files" in {

    val fileOne = FileUpload("matlab.m")
    fileOne.fileType shouldBe FileType.MATLAB
    fileOne.info.packageType shouldBe PackageType.Unsupported
    fileOne.isMasterFile shouldBe false

    val fileTwo = FileUpload("unsupported.blah")
    fileTwo.fileType shouldBe FileType.GenericData
    fileTwo.info.packageType shouldBe PackageType.Unknown
    fileTwo.isMasterFile shouldBe false

    // Temporarily disable CSV/TSV processing

    val fileThree = FileUpload("table.csv")
    fileThree.fileType shouldBe FileType.CSV
    fileThree.info.packageType shouldBe PackageType.CSV
    fileThree.isMasterFile shouldBe false
    fileThree.info.hasWorkflow shouldBe false

    val fileFour = FileUpload("table.tsv")
    fileFour.fileType shouldBe FileType.TSV
    fileFour.info.packageType shouldBe PackageType.CSV
    fileFour.isMasterFile shouldBe false
    fileFour.info.hasWorkflow shouldBe false

  }

}
