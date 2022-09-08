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

class PackageStateSpec extends AnyFlatSpec with Matchers {

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
