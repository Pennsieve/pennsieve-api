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

package com.pennsieve.domain.relational

import akka.stream.testkit.scaladsl.TestSink
import com.pennsieve.api.ApiSuite
import com.pennsieve.db.{ TimeSeriesAnnotation, TimeSeriesLayer }
import com.pennsieve.models.{ Channel, Package, PackageState, PackageType }
import com.pennsieve.timeseries._
import com.github.tminglei.slickpg.Range
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues._
import org.scalatest.EitherValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.SortedSet

class TimeSeriesAnnotationSpec
    extends AnyFlatSpec
    with ApiSuite
    with Matchers
    with BeforeAndAfterEach {

  val timeSeriesId = "N:timeSeries:1"
  var timeSeries: Package = _
  var channels: List[Channel] = _
  var ch1: Channel = _
  var ch2: Channel = _
  var ch3: Channel = _
  var ch4: Channel = _
  var ch5: Channel = _
  var layer: TimeSeriesLayer = _
  var annotation: TimeSeriesAnnotation = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    insecureContainer.dataDB.run(clearDBSchema).await
    secureContainer.db.run(clearDBSchema).await

    layer = insecureContainer.layerManager
      .create(
        timeSeriesId = timeSeriesId,
        name = "Default",
        description = Some("desc")
      )
      .await

    timeSeries = packageManager
      .create(
        name = "testTimeSeries",
        `type` = PackageType.TimeSeries,
        parent = None,
        state = PackageState.READY,
        dataset = dataset,
        ownerId = Some(loggedInUser.id)
      )
      .await
      .value

    channels = (1 to 5).map { i =>
      secureContainer.timeSeriesManager
        .createChannel(
          `package` = timeSeries,
          name = s"Channel$i",
          start = 0,
          end = 10,
          unit = "ms",
          rate = 1.0,
          `type` = "eeg",
          group = None,
          lastAnnotation = 0
        )
        .await
        .value
    }.toList
    ch1 = channels(0)
    ch2 = channels(1)
    ch3 = channels(2)
    ch4 = channels(3)
    ch5 = channels(4)

    annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000L, 2000),
        channelIds = channelIds(ch1, ch2, ch3),
        None
      )(secureContainer.timeSeriesManager)
      .await
      .value
  }

  def channelIds(channels: Channel*): SortedSet[String] =
    channels.map(_.nodeId).to[SortedSet]

  def createAnnotations(
    start: Int,
    end: Int,
    step: Int = 50,
    ts: Package = timeSeries,
    chids: SortedSet[String] = channelIds(ch1, ch4, ch5),
    data: (Int, Int) => Option[AnnotationData] = (_, _) => None
  ): List[TimeSeriesAnnotation] = {
    (start to end by step)
      .sliding(2)
      .map { frame =>
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = ts,
            layerId = layer.id,
            name = "test",
            label = "testLabel",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range(frame(0), frame(1)),
            channelIds = chids,
            data = data(frame(0), frame(1))
          )(secureContainer.timeSeriesManager)
          .await
          .value
      }
      .toList
  }

  behavior of "TimeSeries#Annotations"

  it should "serialize and deserialize annotation data correctly" in {

    val textData: AnnotationData = Text("test")
    val integerData: AnnotationData = Integer(33)

    AnnotationData.serialize(textData) shouldBe """{"value":"test","type":"Text"}"""
    AnnotationData.serialize(integerData) shouldBe """{"value":33,"type":"Integer"}"""

    AnnotationData.deserialize("""{"value":"test","type":"Text"}""") shouldBe textData
    AnnotationData.deserialize("""{"value":33,"type":"Integer"}""") shouldBe integerData
  }

  it should "find annotations by channel ids" in {
    val gottenAnnotations = insecureContainer.timeSeriesAnnotationManager
      .findBy(channelIds = channelIds(ch1, ch2, ch3))
      .await

    gottenAnnotations should have length 1
    gottenAnnotations.head.id should equal(annotation.id)
  }

  it should "find annotations with no description" in {

    val testAnnotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = None,
        userNodeId = "userId",
        range = Range(700, 750),
        channelIds = channelIds(ch1),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    val gottenAnnotations = insecureContainer.timeSeriesAnnotationManager
      .findBy(channelIds = channelIds(ch1))
      .await

    gottenAnnotations should have length 2
    gottenAnnotations should contain(testAnnotation)
  }

  it should "find annotations by id" in {
    val retrievedAnnotation = insecureContainer.timeSeriesAnnotationManager
      .getBy(annotation.id)
      .await

    retrievedAnnotation.value.id should equal(annotation.id)
  }

  it should "update an annotation" in {
    val updatedChannelIds = channelIds(ch3, ch4, ch5)
    val updatedAnnotation =
      annotation.copy(channelIds = updatedChannelIds, name = "updated")

    insecureContainer.timeSeriesAnnotationManager
      .update(timeSeries, updatedAnnotation)(secureContainer.timeSeriesManager)
      .await
    val retrievedAnnotation = insecureContainer.timeSeriesAnnotationManager
      .getBy(annotation.id)
      .await
      .value

    retrievedAnnotation should equal(updatedAnnotation)
  }

  it should "not update an annotation with channels that do not exist" in {
    val updatedChannelIds = SortedSet("bad-channel-id")
    val updatedAnnotation =
      annotation.copy(channelIds = updatedChannelIds, name = "updated")

    val result = insecureContainer.timeSeriesAnnotationManager
      .update(timeSeries, updatedAnnotation)(secureContainer.timeSeriesManager)
      .await
    assert(result.isLeft)
  }

  it should "not create annotations for channels that do not exist" in {
    val result = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000L, 2000),
        channelIds = SortedSet("bad-channel-id"),
        data = None
      )(secureContainer.timeSeriesManager)
    assert(result.await.isLeft)
  }

  it should "detect existence or non-existence of annotations for a particular time series package" in {

    val freshTimeSeries = packageManager
      .create(
        name = "freshTimeSeries",
        `type` = PackageType.TimeSeries,
        parent = None,
        state = PackageState.READY,
        dataset = dataset,
        ownerId = Some(loggedInUser.id)
      )
      .await
      .value

    val freshChannel = secureContainer.timeSeriesManager
      .createChannel(
        `package` = freshTimeSeries,
        name = "Channel",
        start = 0,
        end = 10,
        unit = "ms",
        rate = 1.0,
        `type` = "eeg",
        group = None,
        lastAnnotation = 0
      )
      .await
      .value

    assert(
      !insecureContainer.timeSeriesAnnotationManager
        .hasAnnotations(freshTimeSeries.nodeId)
        .await
    )
    createAnnotations(
      100,
      500,
      ts = freshTimeSeries,
      chids = channelIds(freshChannel)
    )
    assert(
      insecureContainer.timeSeriesAnnotationManager
        .hasAnnotations(freshTimeSeries.nodeId)
        .await
    )
  }

  it should "count the number of annotations via window function correctly" in {

    createAnnotations(100, 500)

    val probe = insecureContainer.timeSeriesAnnotationManager
      .windowAggregate(
        frameStart = 100,
        frameEnd = 500,
        periodLength = 100,
        channelIds = channelIds(ch1),
        layerId = layer.id,
        windowAggregator = WindowAggregator.CountAggregator
      )
      .runWith(TestSink.probe[AnnotationAggregateWindowResult[Double]])

    (100 to 400 by 100).foreach { idx =>
      probe.requestNext(AnnotationAggregateWindowResult(idx, idx + 100, 2.0))
    }
    probe.expectComplete()
  }

  it should "aggregate annotation values by window function" in {
    createAnnotations(100, 200, 10, data = (start, _) => Some(Integer(start)))

    val probe = insecureContainer.timeSeriesAnnotationManager
      .windowAggregate(
        frameStart = 100,
        frameEnd = 200,
        periodLength = 20,
        channelIds = channelIds(ch1),
        layerId = layer.id,
        windowAggregator = WindowAggregator.IntegerAverageAggregator
      )
      .runWith(TestSink.probe[AnnotationAggregateWindowResult[Double]])

    List[Double](105, 125, 145, 165, 185).zipWithIndex.foreach {
      case (v, idx) =>
        val windowStart: Long = 100L + (idx * 20)
        probe.requestNext(
          AnnotationAggregateWindowResult(windowStart, windowStart + 20, v)
        )
    }
    probe.expectComplete()
  }

  it should "aggregate annotation values by window function even if some are null" in {
    val channelGroup =
      insecureContainer.channelGroupManager.create(channelIds(ch1)).await
    insecureContainer.timeSeriesAnnotationManager
      .create(
        timeSeries,
        layer.id,
        "",
        "",
        Some(""),
        "",
        Range(100L, 110L),
        channelGroup.channels,
        None
      )(secureContainer.timeSeriesManager)
      .await

    val probe = insecureContainer.timeSeriesAnnotationManager
      .windowAggregate(
        frameStart = 100,
        frameEnd = 200,
        periodLength = 20,
        channelIds = channelIds(ch1),
        layerId = layer.id,
        windowAggregator = WindowAggregator.IntegerAverageAggregator
      )
      .runWith(TestSink.probe[AnnotationAggregateWindowResult[Double]])

    probe
      .request(1)
      .expectComplete()
  }

  it should "aggregate annotation values by window function even if there are overlaps" in {
    createAnnotations(100, 500)

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(200, 450),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    val probe = insecureContainer.timeSeriesAnnotationManager
      .windowAggregate(
        frameStart = 100,
        frameEnd = 500,
        periodLength = 100,
        channelIds = channelIds(ch1),
        layerId = layer.id,
        windowAggregator = WindowAggregator.CountAggregator
      )
      .runWith(TestSink.probe[AnnotationAggregateWindowResult[Double]])

    (100 to 400 by 100).foreach { idx =>
      if (idx >= 200)
        probe.requestNext(AnnotationAggregateWindowResult(idx, idx + 100, 3.0))
      else
        probe.requestNext(AnnotationAggregateWindowResult(idx, idx + 100, 2.0))
    }
    probe.expectComplete()
  }

  it should "aggregate annotation values by chunk windows function" in {

    createAnnotations(100, 200)

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(301, 350),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(575, 580),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(600, 900),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000, 1200),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeSeries,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(700, 750),
        channelIds = channelIds(ch1, ch4, ch5),
        data = Some(Integer(1))
      )(secureContainer.timeSeriesManager)
      .await

    val chunkedProbe = insecureContainer.timeSeriesAnnotationManager
      .chunkWindowAggregate(
        frameStart = 100,
        frameEnd = 900,
        periodLength = 100,
        channelIds = channelIds(ch1),
        layerId = layer.id,
        windowAggregator = WindowAggregator.CountAggregator
      )
      .runWith(TestSink.probe[AnnotationAggregateWindowResult[Double]])

    chunkedProbe.requestNext(AnnotationAggregateWindowResult(100, 200, 2.0))
    chunkedProbe.requestNext(AnnotationAggregateWindowResult(300, 400, 1.0))
    chunkedProbe.requestNext(AnnotationAggregateWindowResult(500, 900, 3.0))
    chunkedProbe.expectComplete()
  }
}
