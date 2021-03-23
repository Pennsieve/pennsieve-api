package com.pennsieve.models

import com.pennsieve.core.utilities
import org.scalatest.{ FlatSpec, Matchers }

class PackageStateSpec extends FlatSpec with Matchers {

  "FAILED" should "deserialize as ERROR" in {
    PackageState.withName("FAILED") shouldBe PackageState.ERROR
  }

  "UPLOAD_FAILED" should "serialize to ERROR in DTOs" in {
    PackageState.UPLOAD_FAILED.asDTO shouldBe PackageState.ERROR
  }

  "PROCESSING_FAILED" should "serialize to ERROR in DTOs" in {
    PackageState.PROCESSING_FAILED.asDTO shouldBe PackageState.ERROR
  }

}
