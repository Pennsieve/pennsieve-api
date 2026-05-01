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
import com.pennsieve.dtos.{ ChannelDTO, ModelPropertiesDTO, ModelPropertyRO }
import com.pennsieve.helpers.{ APIContainers, TimeSeriesHelper }
import com.pennsieve.models.{
  Channel,
  CognitoId,
  DBPermission,
  Dataset,
  ModelProperty,
  NodeCodes,
  Organization,
  OrganizationUser,
  Package,
  PackageState,
  PackageType,
  User
}
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.collection.SortedSet

class TestTimeSeriesController extends BaseApiUnitTest {

  var loggedInUser: User = _
  var loggedInOrganization: Organization = _
  var loggedInJwt: String = _
  var secureContainer: APIContainers.SecureAPIContainer = _

  override def beforeAll(): Unit = {
    super.beforeAll()
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

  override def beforeEach(): Unit = {
    super.beforeEach()
    state.clear()
    val orgId = state.newId()
    loggedInOrganization = Organization(
      nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
      name = "Test Organization",
      slug = "test-org",
      id = orgId
    )
    state.organizations.put(orgId, loggedInOrganization)

    val uid = state.newId()
    loggedInUser = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = "test@test.com",
      firstName = "first",
      middleInitial = None,
      lastName = "last",
      degree = None,
      credential = "cred",
      color = "",
      url = "http://test.com",
      authyId = 0,
      isSuperAdmin = false,
      isIntegrationUser = false,
      preferredOrganizationId = None,
      status = true,
      orcidAuthorization = None,
      cognitoId = Some(CognitoId.UserPoolId(UUID.randomUUID())),
      id = uid
    )
    state.users.put(uid, loggedInUser)
    state.orgUserPermissions
      .put((loggedInOrganization.id, loggedInUser.id), DBPermission.Administer)
    state.orgUsers.put(
      (loggedInOrganization.id, loggedInUser.id),
      OrganizationUser(
        loggedInOrganization.id,
        loggedInUser.id,
        DBPermission.Administer
      )
    )
    loggedInJwt = mintUserJwt(loggedInUser, loggedInOrganization)
    secureContainer = secureContainerBuilder(loggedInUser, loggedInOrganization)
    secureContainer.datasetStatusManager.resetDefaultStatusOptions.await.value
  }

  private def packageManager: com.pennsieve.managers.PackageManager =
    secureContainer.packageManager
  private def timeSeriesManager: com.pennsieve.managers.TimeSeriesManager =
    secureContainer.timeSeriesManager

  private def createDataSet(name: String): Dataset =
    secureContainer.datasetManager.create(name, Some("desc")).await.value

  private def createTimeSeriesPackage(
    numberOfChannels: Int = 1
  ): (Package, List[Channel]) = {
    val ds = createDataSet("My DataSet")
    val pkg = packageManager
      .create(
        "Baz",
        PackageType.TimeSeries,
        PackageState.READY,
        ds,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val channels = (1 to numberOfChannels).map { _ =>
      timeSeriesManager
        .createChannel(
          pkg,
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
    (pkg, channels)
  }

  // ---------------- Tests --------------------------------------------------

  test("calculate the minimum package time for a package") {
    val (pkg, _) = createTimeSeriesPackage(10)
    val startTime =
      TimeSeriesHelper.getPackageStartTime(pkg, secureContainer).await
    startTime should be(Right(1000))
  }

  test("calculate the minimum package time for a list of channels") {
    val (_, channelList) = createTimeSeriesPackage(10)
    val startTime = TimeSeriesHelper.getPackageStartTime(channelList)
    startTime should be(1000)
  }

  test("reset a channel start time") {
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

  test("reset an annotation start time") {
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

  test("get a channel") {
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

  test("get a channel starting at epoch if flag is set") {
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
      val expected = ChannelDTO(
        TimeSeriesHelper.resetChannelStartTime(packageStartTime)(channel),
        tsPkg
      )
      result should equal(expected)
    }
  }

  test("get channels") {
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

  test("get channels starting at epoch if flag is set") {
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
            TimeSeriesHelper.resetChannelStartTime(
              TimeSeriesHelper.getPackageStartTime(origChannels)
            )
          ),
          tsPkg
        )
      )
    }
  }

  test("create channel") {
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
    postJson(
      s"/$tsPkg/channels",
      write(request),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      val created = parsedBody.extract[ChannelDTO]
      created.content.name should equal(request.name)
    }

    val channels = (0 to 4).map { i =>
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
    postJson(
      s"/$tsPkg/channels",
      write(channels),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      parsedBody.extract[List[ChannelDTO]] should have size 5
    }
  }

  test("update a channel") {
    val (tsPkg, origChannels) = createTimeSeriesPackage()
    val updatedChannel =
      ChannelDTO(origChannels.head.copy(name = "new name", end = 12345L), tsPkg)
    putJson(
      s"/${tsPkg.nodeId}/channels/${updatedChannel.content.id}",
      write(updatedChannel.content),
      headers = authorizationHeader(loggedInJwt)
    ) {
      val channel = parsedBody.extract[ChannelDTO]
      channel should equal(updatedChannel)
      val updated =
        timeSeriesManager.getChannel(origChannels.head.id, tsPkg).await.value
      updated.end should equal(12345L)
    }
  }

  test("fail to update a channel with a blank name") {
    val (tsPkg, origChannels) = createTimeSeriesPackage(2)
    val updatedChannel = ChannelDTO(origChannels.head.copy(name = ""), tsPkg)
    putJson(
      s"/${tsPkg.nodeId}/channels/${updatedChannel.content.id}",
      write(updatedChannel.content),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("channel name must not be blank")
    }
  }

  test("update multiple channels") {
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
      channels should contain theSameElementsAs List(
        firstChannel,
        secondChannel
      )
      val updatedPackageChannels =
        timeSeriesManager.getChannels(tsPkg).await.value
      val first =
        updatedPackageChannels.find(_.nodeId == firstChannel.content.id).get
      first.name should be("new name")
      first.end should be(12345L)
      val second =
        updatedPackageChannels.find(_.nodeId == secondChannel.content.id).get
      second.name should be("another new name")
      second.end should be(54321L)
    }
  }

  test("fail to update multiple channels with a blank name") {
    val (tsPkg, origChannels) = createTimeSeriesPackage(2)
    val firstChannel = ChannelDTO(origChannels.head.copy(name = ""), tsPkg)
    val secondChannel = ChannelDTO(origChannels.last.copy(name = ""), tsPkg)
    putJson(
      s"/${tsPkg.nodeId}/channels",
      write(List(firstChannel.content, secondChannel.content)),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
      body should include("channel names must not be blank")
    }
  }

  test("update a channel's properties") {
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
    putJson(
      s"/${tsPkg.nodeId}/channels/${originalChannelNode.nodeId}/properties",
      write(properties),
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

  test("delete an existing channel") {
    val (tsPkg, channels) = createTimeSeriesPackage()
    delete(
      s"/${tsPkg.nodeId}/channels/${channels.head.nodeId}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }
  }
}
