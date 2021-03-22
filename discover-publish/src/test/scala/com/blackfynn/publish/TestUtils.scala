package com.blackfynn.publish

import org.scalatest.{ Matchers, Suite, WordSpec }
import cats._
import cats.implicits._

class TestUtils extends WordSpec with Matchers {

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
