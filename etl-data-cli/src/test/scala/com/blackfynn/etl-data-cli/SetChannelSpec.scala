// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.etl.`data-cli`

import com.pennsieve.test.helpers.AwaitableImplicits._
import com.pennsieve.etl.`data-cli`.SetChannel.ChannelInfo
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ Dataset, Package }
import com.pennsieve.traits.PostgresProfile.api._
import java.io.{ File => JavaFile }

import com.pennsieve.models.Channel
import org.scalatest.{ FlatSpec, Matchers }

class SetChannelSpec extends FlatSpec with Matchers {

  "decodeChannelInfo" should "parse a JSON file into a ChannelInfo without an id" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/channel-info.json").getPath)
    val result: ChannelInfo = SetChannel.decodeChannelInfo(file).await

    result.id should equal(None)
    result.name should equal("channel-1")
    result.start should equal(1000L)
    result.end should equal(6000L)
    result.rate should equal(1000.0)
    result.unit should equal("uV")
    result.`type` should equal("CONTINUOUS")
    result.group should equal(None)
    result.lastAnnotation should equal(Some(0L))
    result.properties should equal(None)
    result.spikeDuration should equal(None)
  }

  "decodeChannelInfo" should "parse a JSON file into a ChannelInfo with an id" in {
    val file: JavaFile = new JavaFile(
      getClass.getResource("/inputs/update-channel-info.json").getPath
    )
    val result: ChannelInfo = SetChannel.decodeChannelInfo(file).await

    result.id should equal(Some(1))
    result.name should equal("channel-1")
    result.start should equal(2000L)
    result.end should equal(6000L)
    result.rate should equal(1000.0)
    result.unit should equal("uV")
    result.`type` should equal("CONTINUOUS")
    result.group should equal(None)
    result.lastAnnotation should equal(Some(1L))
    result.properties should equal(None)
    result.spikeDuration should equal(None)
  }

  "decodeChannelInfo" should "should fail on a missing file" in {
    val file: JavaFile = new JavaFile("missing.json")

    assertThrows[Exception] {
      SetChannel.decodeChannelInfo(file).await
    }
  }

  "decodeChannelInfo" should "should fail on a file with invalid JSON" in {
    val file: JavaFile =
      new JavaFile(getClass.getResource("/inputs/asset-info.json").getPath)

    assertThrows[Exception] {
      SetChannel.decodeChannelInfo(file).await
    }
  }

  "parse" should "should create a config object" in {
    val args = Array(
      "--package-id",
      "6",
      "--organization-id",
      "10",
      "--channel-info",
      "channel-info.json",
      "--output-file",
      "channel.json"
    )
    val config = SetChannel.CLIConfig(
      new JavaFile("channel-info.json"),
      new JavaFile("channel.json"),
      6,
      10
    )

    SetChannel.parse(args).await should equal(config)
  }

  "parse" should "should fail with missing arguments" in {
    val args = Array("--package-id", "6", "--organization-id", "10")

    assertThrows[ScoptParsingFailure] {
      SetChannel.parse(args).await
    }
  }

}

class SetChannelDatabaseSpec extends DataCLIDatabaseSpecHarness {

  var dataset: Dataset = _
  var `package`: Package = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    dataset = createDataset
    `package` = createPackage(dataset)
  }

  "createOrUpdateBynameAndPackageId" should "should create a Channel to the database and return a Channel when the Channel does not exists already" in {
    val data: ChannelInfo = ChannelInfo(
      name = "channel-1",
      start = 1000L,
      end = 6000L,
      rate = 1000.0,
      unit = "uV",
      `type` = "CONTINUOUS",
      group = None,
      lastAnnotation = Some(0L),
      properties = None,
      spikeDuration = None
    )

    val result = dataCLIContainer.db
      .run(
        SetChannel.createOrUpdateByNameAndPackageId(
          `package`.id,
          organization.id,
          data
        )(dataCLIContainer)
      )
      .await

    result.id should not equal (0)
    result.name should equal(data.name)
  }

  "createOrUpdateBynameAndPackageId" should "update a pre-existing Channel in the database and return the updated Channel" in {
    val channelId: Int = 1

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
      properties = Nil,
      id = channelId
    )

    val channel: Channel =
      dataCLIContainer.db.run((channels returning channels) += row).await

    val data: ChannelInfo = ChannelInfo(
      id = Some(channelId),
      name = channel.name,
      start = 2000L,
      end = 8000L,
      rate = channel.rate,
      unit = channel.unit,
      `type` = channel.`type`,
      group = Some("new-group"),
      lastAnnotation = Some(1L),
      properties = None,
      spikeDuration = Some(10)
    )

    val result = dataCLIContainer.db
      .run(
        SetChannel.createOrUpdateByNameAndPackageId(
          `package`.id,
          organization.id,
          data
        )(dataCLIContainer)
      )
      .await

    result.id should equal(channelId)
    result.name should equal(channel.name)
    result.start should equal(1000)
    result.end should equal(8000)
    result.group should equal(data.group)
    result.spikeDuration should equal(data.spikeDuration)

  }

  "update" should "update a Channel in the database and return the updated Channel" in {
    val channelId: Int = 1

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
      properties = Nil,
      id = channelId
    )

    val channel: Channel =
      dataCLIContainer.db.run((channels returning channels) += row).await

    val data: ChannelInfo = ChannelInfo(
      id = Some(channelId),
      name = channel.name,
      start = 2000L,
      end = 8000L,
      rate = channel.rate,
      unit = channel.unit,
      `type` = channel.`type`,
      group = Some("new-group"),
      lastAnnotation = Some(1L),
      properties = None,
      spikeDuration = Some(10)
    )

    val result = dataCLIContainer.db
      .run(
        SetChannel.update(`package`.id, organization.id, channelId, data)(
          dataCLIContainer
        )
      )
      .await

    result.id should equal(channelId)
    result.name should equal(channel.name)
    result.start should equal(1000)
    result.end should equal(8000)
    result.group should equal(data.group)
    result.spikeDuration should equal(data.spikeDuration)

  }
}
