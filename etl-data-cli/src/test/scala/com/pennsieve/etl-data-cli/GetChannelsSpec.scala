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

package com.pennsieve.etl.`data-cli`

import com.pennsieve.test.helpers.AwaitableImplicits._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ Dataset, Package }
import com.pennsieve.traits.PostgresProfile.api._

import java.io.{ File => JavaFile }
import com.pennsieve.models.Channel
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GetChannelsSpec extends AnyFlatSpec with Matchers {

  "parse" should "create a config object" in {
    val args = Array(
      "--package-id",
      "6",
      "--organization-id",
      "10",
      "--output-file",
      "channels.json"
    )
    val config = GetChannels.CLIConfig(new JavaFile("channels.json"), 6, 10)

    GetChannels.parse(args).await should equal(config)
  }

  "parse" should "fail with missing arguments" in {
    val args = Array("--package-id", "6", "--organization-id", "10")

    assertThrows[ScoptParsingFailure] {
      GetChannels.parse(args).await
    }
  }

}

class GetChannelsDatabaseSpec extends DataCLIDatabaseSpecHarness {

  var dataset: Dataset = _
  var `package`: Package = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
    `package` = createPackage(dataset)
  }

  "get" should "get all the channels for a package" in {
    val row = Channel(
      nodeId = "N:channel:d4a6a17a-3cf1-11e8-b467-0ed5f89f718b",
      packageId = `package`.id,
      name = "channel-1",
      start = 1000L,
      end = 6000L,
      unit = "uV",
      rate = 1000.0,
      `type` = "CONTINUOUS",
      group = None,
      lastAnnotation = 0L,
      spikeDuration = None,
      properties = Nil
    )

    val channel: Channel =
      dataCLIContainer.db.run((channels returning channels) += row).await

    val result = dataCLIContainer.db
      .run(GetChannels.query(`package`.id, organization.id)(dataCLIContainer))
      .await

    result should equal(List(channel))

  }
}
