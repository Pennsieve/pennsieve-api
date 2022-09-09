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

package com.pennsieve.publish

import cats._
import cats.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TestUtils extends AnyWordSpec with Matchers {

  "joinKeys" should {

    "separate keys by exactly one slash" in {
      utils.joinKeys("0/1", "file.txt") shouldBe "0/1/file.txt"
      utils.joinKeys("0/1", "/file.txt") shouldBe "0/1/file.txt"
      utils.joinKeys("0/1/", "file.txt") shouldBe "0/1/file.txt"
      utils.joinKeys("0/1/", "/file.txt") shouldBe "0/1/file.txt"
    }

    "handle sequences of keys" in {
      utils.joinKeys(List("0/1", "a/b/", "/file.txt")) shouldBe "0/1/a/b/file.txt"
    }
  }
}
