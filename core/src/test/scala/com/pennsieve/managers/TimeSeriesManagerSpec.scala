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

package com.pennsieve.managers

import com.pennsieve.models.{ Channel, PackageType }
import com.pennsieve.models.Package
import org.scalatest.matchers.should.Matchers._
import org.scalatest.EitherValues._
import scala.collection.SortedSet
import scala.concurrent.ExecutionContext.Implicits.global

class TimeSeriesManagerSpec extends BaseManagerSpec {

  def createChannel(
    p: Package,
    name: String = "ch 1"
  )(
    tm: TimeSeriesManager
  ): Channel = {
    tm.createChannel(p, name, 0, 10, "ms", 1.0, "eeg", None, 0).await.value
  }

  "a channel" should "be retrievable from the database" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel = createChannel(p)(tm)

    assert(tm.getChannel(channel.id, p).await.isRight)
    assert(tm.getChannelByNodeId(channel.nodeId, p).await.isRight)
  }

  "a channel's package" should "be retrievable" in {
    val user = createUser()
    val tm = timeSeriesManager()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel = createChannel(p)(tm)

    assert(p == pm.get(channel.packageId).await.value)
  }

  "updating a channel" should "succeed" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel = createChannel(p)(tm)

    val updated =
      tm.updateChannel(channel.copy(name = "Updated Name"), p).await.value

    assert(tm.getChannel(channel.id, p).await.value.name == updated.name)
  }

  "updating multiple channels" should "succeed" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel1 = createChannel(p)(tm)
    val channel2 = createChannel(p)(tm)
    val channel3 = createChannel(p)(tm)

    val updatedList = List(
      channel1.copy(name = "Updated channel1 Name"),
      channel2.copy(name = "Updated channel2 Name"),
      channel3.copy(name = "Updated channel3 Name")
    )

    tm.updateChannels(updatedList, p).await

    assert(
      tm.getChannel(channel1.id, p).await.value.name == "Updated channel1 Name"
    )
    assert(
      tm.getChannel(channel2.id, p).await.value.name == "Updated channel2 Name"
    )
    assert(
      tm.getChannel(channel3.id, p).await.value.name == "Updated channel3 Name"
    )
  }

  "deleting a channel" should "succeed" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel = createChannel(p)(tm)

    val updated = tm.deleteChannel(channel, p).await

    assert(tm.getChannel(channel.id, p).await.isLeft)
    assert(tm.getChannelByNodeId(channel.nodeId, p).await.isLeft)
  }

  "get a package's channel" should "succeed and not get another package's channels" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val other = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )

    val channelOne = createChannel(p)(tm)
    val channelTwo = createChannel(p, "ch 2")(tm)
    val channelThree = createChannel(p, "ch 3")(tm)
    val channelFour = createChannel(other, "ch other")(tm)

    tm.getChannels(p)
      .await
      .value should contain only (channelOne, channelTwo, channelThree)
  }

  "get channels" should "fail when channels are not in package" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val other = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )

    val channelOne = createChannel(p)(tm)
    val channelTwo = createChannel(p, "ch 2")(tm)
    val channelThree = createChannel(p, "ch 3")(tm)
    val channelFour = createChannel(other, "ch other")(tm)

    tm.getChannelsByNodeIds(p, SortedSet(channelOne.nodeId, channelTwo.nodeId))
      .await
      .value should contain only (channelOne, channelTwo)

    assert(
      tm.getChannelsByNodeIds(
          p,
          SortedSet(channelOne.nodeId, channelFour.nodeId)
        )
        .await
        .isLeft
    )
  }

  "a channel on a timeseries package created by a user" should "readable by another user in the same group" in {
    val user = createUser()
    val tm = timeSeriesManager()

    val dataset = createDataset(user = user)
    val p = createPackage(
      user = user,
      dataset = dataset,
      `type` = PackageType.TimeSeries
    )
    val channel = createChannel(p)(tm)

    val userTwo =
      createUser(email = "userTwo@test.com", datasets = List(dataset))
    val tmTwo = timeSeriesManager()
    val pmTwo = packageManager(user = userTwo)
    val pRetrieved = pmTwo.get(p.id).await.value

    assert(tm.getChannel(channel.id, pRetrieved).await.value == channel)
  }

}
