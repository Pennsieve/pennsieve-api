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

package com.pennsieve.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatra.ActionResult

class TestPackagesControllerScanPolicy extends AnyFlatSpec with Matchers {
  import PackagesController._

  "scanStatusBlocks" should "block only infected and failed files" in {
    scanStatusBlocks(Some("infected")) shouldBe true
    scanStatusBlocks(Some("failed")) shouldBe true
    Seq("clean", "format_validated", "unscanned", "not_required", "pending", "scanning")
      .foreach(s => scanStatusBlocks(Some(s)) shouldBe false)
    scanStatusBlocks(None) shouldBe false
  }

  "scanStatusWaits" should "hold pending and scanning files with the scan wait on" in {
    scanStatusWaits(Some("pending"), scanWaitOnPending = true) shouldBe true
    scanStatusWaits(Some("scanning"), scanWaitOnPending = true) shouldBe true
    Seq("clean", "format_validated", "unscanned", "not_required", "infected", "failed")
      .foreach(s => scanStatusWaits(Some(s), scanWaitOnPending = true) shouldBe false)
    scanStatusWaits(None, scanWaitOnPending = true) shouldBe false
  }

  it should "hold nothing with the scan wait off" in {
    scanStatusWaits(Some("pending"), scanWaitOnPending = false) shouldBe false
    scanStatusWaits(Some("scanning"), scanWaitOnPending = false) shouldBe false
  }

  "scanPending" should "answer 409 with download-service's body" in {
    val result: ActionResult = scanPending(Some("scanning"))
    result.status shouldBe 409
    result.body shouldBe ScanPendingError(
      "scan_pending",
      "scanning",
      ScanPendingMessage
    )
  }
}
