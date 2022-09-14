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

import com.pennsieve.db.TimeSeriesAnnotation
import com.pennsieve.models.{ Channel, ModelProperty, Package }
import com.pennsieve.dtos.{ ChannelDTO, ModelPropertiesDTO, ModelPropertyRO }

import scala.collection.SortedSet
import com.pennsieve.helpers.{ DataSetTestMixin, TimeSeriesHelper }
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.TimeSeries
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec

class TestTimeSeriesController
    extends AnyFlatSpec
    with ApiSuite
    with DataSetTestMixin {

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new TimeSeriesController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher,
        system
      ),
      "/*"
    )
  }

  def createTimeSeriesPackage(
    numberOfChannels: Int = 1
  ): (Package, List[Channel]) = {
    val dataset = createDataSet("My DataSet")

    val `package` = packageManager
      .create("Baz", TimeSeries, READY, dataset, Some(loggedInUser.id), None)
      .await
      .value

    val channels = (1 to numberOfChannels).map { i =>
      timeSeriesManager
        .createChannel(
          `package`,
          "test channel",
          1000,
          10000,
          "unit",
          265.5,
          "eeg",
          None,
          0
        )
        .await
        .value
    }.toList

    (`package`, channels)
  }

  behavior of "Controller"

  it should "calculate the minimum package time for a package" in {
    val (pkg, _) = createTimeSeriesPackage(10)
    val startTime =
      TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).await

    startTime should be(Right(1000))
  }

  it should "calculate the minimum package time for a list of channels" in {
    val (_, channelList) = createTimeSeriesPackage(10)
    val startTime = TimeSeriesHelper.getPackageStartTime(channelList)

    startTime should be(1000)
  }

  it should "reset a channel start time" in {
    val channel = Channel(
      nodeId = "",
      packageId = 0,
      name = "",
      start = 1000000000L,
      end = 293000000000L,
      unit = "",
      rate = 0,
      `type` = "",
      group = None,
      lastAnnotation = 33000000000L
    )

    val resetChannel =
      TimeSeriesHelper.resetChannelStartTime(10000000)(channel)

    resetChannel.start should be(990000000L)
    resetChannel.end should be(292990000000L)
    resetChannel.lastAnnotation should be(32990000000L)
    resetChannel.createdAt should be(TimeSeriesHelper.Epoch)
  }

  it should "reset an annotation start time" in {
    val annotation = TimeSeriesAnnotation(
      id = 0,
      timeSeriesId = "",
      channelIds = SortedSet.empty,
      layerId = 0,
      name = "",
      label = "",
      description = None,
      userId = None,
      start = 23000000000000L,
      end = 400000000000000L,
      data = None,
      linkedPackage = None
    )

    val resetAnnotation =
      TimeSeriesHelper.resetAnnotationStartTime(10000000)(annotation)

    resetAnnotation.start should be(22999990000000L)
    resetAnnotation.end should be(399999990000000L)
  }

  behavior of "Channels"

  // get channel
  it should "get a channel" in {
    val (tsPkg, channels) = createTimeSeriesPackage()
    val channel = channels.head

    get(
      s"/${tsPkg.nodeId}/channels/${channel.nodeId}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val result = parsedBody.extract[ChannelDTO]
      result should equal(ChannelDTO(channel, tsPkg))
    }
  }

  it should "get a channel starting at epoch if flag is set" in {
    val (tsPkg, channels) = createTimeSeriesPackage()
    val channel = channels.head

    val packageStartTime = TimeSeriesHelper
      .getPackageStartTime(tsPkg, secureContainer)
      .await
      .value

    get(
      s"/${tsPkg.nodeId}/channels/${channel.nodeId}?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val result = parsedBody.extract[ChannelDTO]
      val expected =
        ChannelDTO(
          TimeSeriesHelper.resetChannelStartTime(packageStartTime)(channel),
          tsPkg
        )

      result should equal(expected)
    }
  }

  it should "get channels" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage(5)

    get(
      s"/${tsPkg.nodeId}/channels",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val channels = parsedBody.extract[List[ChannelDTO]]

      origChannels.size should equal(5)
      channels should have size 5

      channels should equal(ChannelDTO(origChannels, tsPkg))
    }
  }

  it should "get channels starting at epoch if flag is set" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage(5)

    get(
      s"/${tsPkg.nodeId}/channels?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val channels = parsedBody.extract[List[ChannelDTO]]

      origChannels.size should equal(5)
      channels should have size 5

      channels should equal(
        ChannelDTO(
          origChannels.map(
            TimeSeriesHelper
              .resetChannelStartTime(
                TimeSeriesHelper.getPackageStartTime(origChannels)
              )
          ),
          tsPkg
        )
      )
    }
  }

  it should "create channel" in {
    val tsPkg = createTimeSeriesPackage()._1.nodeId

    val request = TimeSeriesChannelWriteRequest(
      "new channel",
      1000,
      10000,
      "unit",
      256.5,
      "eeg",
      0,
      None,
      None,
      List()
    )
    val json = write(request)

    postJson(
      s"/$tsPkg/channels",
      json,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)

      val created = parsedBody.extract[ChannelDTO]
      created.content.name should equal(request.name)
    }

    val channels = 0 to 4 map { i =>
      TimeSeriesChannelWriteRequest(
        s"new channel $i",
        1000 + i,
        10000 + i,
        "unit",
        256.5 + i,
        "eeg",
        0 + i,
        None,
        None,
        List()
      )
    }

    val channelsJson = write(channels)

    postJson(
      s"/$tsPkg/channels",
      channelsJson,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[List[ChannelDTO]] should have size 5
    }
  }

  // update channels
  it should "update a channel" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage()

    val updatedChannel =
      ChannelDTO(origChannels.head.copy(name = "new name", end = 12345L), tsPkg)
    val request = write(updatedChannel.content)

    putJson(
      s"/${tsPkg.nodeId}/channels/${updatedChannel.content.id}",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val channel = parsedBody.extract[ChannelDTO]
      channel should equal(updatedChannel)
      val updated = timeSeriesManager
        .getChannel(origChannels.head.id, tsPkg)
        .await
        .value
      updated.end should equal(12345L)
    }
  }

  it should "fail to update a channel with a blank name" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage(2)

    val updatedChannel =
      ChannelDTO(origChannels.head.copy(name = ""), tsPkg)
    val request = write(updatedChannel.content)

    putJson(
      s"/${tsPkg.nodeId}/channels/${updatedChannel.content.id}",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("channel name must not be blank")
    }
  }

  it should "update multiple channels" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage(2)

    val firstChannel =
      ChannelDTO(origChannels.head.copy(name = "new name", end = 12345L), tsPkg)
    val secondChannel = ChannelDTO(
      origChannels.last.copy(name = "another new name", end = 54321L),
      tsPkg
    )

    val request = write(List(firstChannel.content, secondChannel.content))

    putJson(
      s"/${tsPkg.nodeId}/channels",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val channels = parsedBody.extract[List[ChannelDTO]]
      channels should contain theSameElementsAs (List(
        firstChannel,
        secondChannel
      ))

      val updatedPackageChannels =
        timeSeriesManager.getChannels(tsPkg).await.value

      val firstPackageChannel =
        updatedPackageChannels.find(_.nodeId == firstChannel.content.id).get
      firstPackageChannel.name should be("new name")
      firstPackageChannel.end should be(12345L)

      val secondPackageChannel =
        updatedPackageChannels.find(_.nodeId == secondChannel.content.id).get
      secondPackageChannel.name should be("another new name")
      secondPackageChannel.end should be(54321L)
    }
  }

  it should "fail to update multiple channels with a blank name" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage(2)

    val firstChannel =
      ChannelDTO(origChannels.head.copy(name = ""), tsPkg)
    val secondChannel = ChannelDTO(origChannels.last.copy(name = ""), tsPkg)

    val request = write(List(firstChannel.content, secondChannel.content))

    putJson(
      s"/${tsPkg.nodeId}/channels",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("channel names must not be blank")
    }
  }

  // update channel's properties
  it should "update a channel's properties" in {
    val (tsPkg, origChannels) = createTimeSeriesPackage()
    val originalChannelNode = origChannels.head

    val properties = List(
      ModelPropertyRO(
        key = "test",
        value = "test",
        dataType = Some("string"),
        category = Some("test"),
        fixed = Some(false),
        hidden = Some(false)
      )
    )

    val request = write(properties)

    putJson(
      s"/${tsPkg.nodeId}/channels/${originalChannelNode.nodeId}/properties",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val resultProperties =
        (parsedBody \ "properties").extract[List[ModelPropertiesDTO]]
      val mergedProperties = ModelProperty.merge(
        originalChannelNode.properties,
        properties.map(ModelPropertyRO.fromRequestObject)
      )

      resultProperties should equal(
        ModelPropertiesDTO.fromModelProperties(mergedProperties)
      )
    }
  }

  // delete channel
  it should "delete an existing channel" in {
    val (tsPkg, channels) = createTimeSeriesPackage()

    delete(
      s"/${tsPkg.nodeId}/channels/${channels.head.nodeId}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }
  }

}
