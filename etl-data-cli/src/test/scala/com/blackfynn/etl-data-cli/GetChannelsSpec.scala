// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.etl.`data-cli`

import com.pennsieve.test.helpers.AwaitableImplicits._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ Dataset, Package }
import com.pennsieve.traits.PostgresProfile.api._
import java.io.{ File => JavaFile }

import com.pennsieve.models.Channel
import org.scalatest.{ FlatSpec, Matchers }

class GetChannelsSpec extends FlatSpec with Matchers {

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
