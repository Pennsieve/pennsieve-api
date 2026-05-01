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

import com.github.tminglei.slickpg.Range
import com.pennsieve.db.TimeSeriesLayer
import com.pennsieve.dtos.PagedResponse
import com.pennsieve.helpers.{ APIContainers, TimeSeriesHelper }
import com.pennsieve.models.{
  Channel,
  CognitoId,
  DBPermission,
  Dataset,
  NodeCodes,
  Organization,
  OrganizationUser,
  Package,
  PackageState,
  PackageType,
  User
}
import com.pennsieve.timeseries.Integer
import org.json4s.JsonAST.JValue
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.util.UUID
import scala.collection.SortedSet

class TimeSeriesControllerAnnotationSpecs extends BaseApiUnitTest {

  var loggedInUser: User = _
  var loggedInOrganization: Organization = _
  var loggedInJwt: String = _
  var secureContainer: APIContainers.SecureAPIContainer = _

  var dataset: Dataset = _
  var timeseriesPackage: Package = _
  var collectionPackage: Package = _
  var layer: TimeSeriesLayer = _
  var collectionLayer: TimeSeriesLayer = _
  var channels: List[Channel] = _
  var collectionChannels: List[Channel] = _
  var channelOneNodeId: String = _
  var channelTwoNodeId: String = _
  var collectionChannelOneNodeId: String = _
  var collectionChannelTwoNodeId: String = _

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
    setUpFixtures()
  }

  private def setUpFixtures(): Unit = {
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

    dataset = secureContainer.datasetManager
      .create("timeseries", Some("desc"))
      .await
      .value

    timeseriesPackage = secureContainer.packageManager
      .create(
        "testTimeSeries",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    channels = (1 to 2).map { i =>
      secureContainer.timeSeriesManager
        .createChannel(
          timeseriesPackage,
          s"testchannel$i",
          1000,
          2000,
          "unit",
          1.0,
          "type",
          None,
          1000
        )
        .await
        .value
    }.toList
    channelOneNodeId = channels.head.nodeId
    channelTwoNodeId = channels(1).nodeId

    layer = insecureContainer.layerManager
      .create(timeseriesPackage.nodeId, "testLayer", Some("desc"))
      .await

    collectionPackage = secureContainer.packageManager
      .create(
        "testCollection",
        PackageType.Collection,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    collectionChannels = (1 to 2).map { i =>
      secureContainer.timeSeriesManager
        .createChannel(
          collectionPackage,
          s"collectionChannel$i",
          1000,
          2000,
          "unit",
          1.0,
          "type",
          None,
          1000
        )
        .await
        .value
    }.toList
    collectionChannelOneNodeId = collectionChannels.head.nodeId
    collectionChannelTwoNodeId = collectionChannels(1).nodeId

    collectionLayer = insecureContainer.layerManager
      .create(collectionPackage.nodeId, "collectionLayer", Some("desc"))
      .await
  }

  // ============== Annotations =============================================

  test("create an annotation") {
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      compactRender(parsedBody \ "label") should include("newLabel")
    }

    get(
      s"/${timeseriesPackage.nodeId}/hasAnnotations",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      assert(body.contains("true"))
    }
  }

  test("create an annotation starting at epoch if flag is set") {
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 100,
         | "end": 200
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?startAtEpoch=true",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      (parsedBody \ "start").extract[Long] should be(100L)
      (parsedBody \ "end").extract[Long] should be(200L)

      val annotationId = (parsedBody \ "id").extract[Int]
      val saved = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get
      saved.start should be(1100)
      saved.end should be(1200)
    }

    get(
      s"/${timeseriesPackage.nodeId}/hasAnnotations",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      assert(body.contains("true"))
    }
  }

  test("create an annotation with a linked package") {
    val slide = secureContainer.packageManager
      .create(
        "a slide",
        PackageType.Slide,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200,
         | "linkedPackage": "${slide.nodeId}"
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      compactRender(parsedBody \ "label") should include("newLabel")
      compactRender(parsedBody \ "linkedPackage") should include(slide.nodeId)
    }
  }

  test("find no annotations for a package without any annotations") {
    val anotherTsPackage = secureContainer.packageManager
      .create(
        "testTimeSeries2",
        PackageType.TimeSeries,
        PackageState.READY,
        dataset,
        Some(loggedInUser.id),
        None
      )
      .await
      .value

    get(
      s"/${anotherTsPackage.nodeId}/hasAnnotations",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      assert(body.contains("false"))
    }
  }

  test("create an annotation with data") {
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200,
         | "data": { "value": 1, "type": "Integer" }
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      compactRender(parsedBody \ "label") should include("newLabel")
    }
  }

  test("check that channel ids belong to this timeseries package") {
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "this-is-not-a-real-id"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("update an annotation") {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000L, 2000L),
        channelIds = SortedSet(channelOneNodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200
         |}""".stripMargin

    putJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (parsedBody \ "start").extract[Long] should be(1100L)
      (parsedBody \ "end").extract[Long] should be(1200L)
      (parsedBody \ "name").extract[String] should be("testAnnotation")
      (parsedBody \ "channelIds")
        .extract[List[String]] should contain theSameElementsAs List(
        channelOneNodeId,
        channelTwoNodeId
      )

      val annotationId = (parsedBody \ "id").extract[Int]
      val saved = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get
      saved.start should be(1100L)
      saved.end should be(1200L)
      saved.name should be("testAnnotation")
    }
  }

  test("update an annotation starting at epoch if flag is set") {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000L, 2000L),
        channelIds = SortedSet(channelOneNodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": ["$channelOneNodeId", "$channelTwoNodeId"],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 100,
         | "end": 200
         |}""".stripMargin

    putJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}?startAtEpoch=true",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (parsedBody \ "start").extract[Long] should be(100L)
      (parsedBody \ "end").extract[Long] should be(200L)

      val annotationId = (parsedBody \ "id").extract[Int]
      val saved = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get
      saved.start should be(1100)
      saved.end should be(1200)
    }
  }

  test("get an annotation") {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "get me",
        label = "a label",
        description = Some("a description"),
        userNodeId = loggedInUser.nodeId,
        range = Range[Long](1100L, 1200L),
        channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
        None
      )(secureContainer.timeSeriesManager)
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      body should include("a label")
    }
  }

  test("get an annotation starting at epoch if flag is set") {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "get me",
        label = "a label",
        description = Some("a description"),
        userNodeId = loggedInUser.nodeId,
        range = Range[Long](1100L, 1200L),
        channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
        None
      )(secureContainer.timeSeriesManager)
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      body should include("a label")
    }
  }

  // Linked-package thumbnail enrichment uses fileManager.getViews against the
  // linked package — tractable but not exercised by the simpler suite.
  test("get an annotation with a linked package")(pending)

  test("get annotations") {
    (0 to 5).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range[Long](10L * index + 1000, 10 * index + 1009),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=1025&end=1050&layerName=testLayer&channelIds=$channelOneNodeId",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (2 to 4).foreach { index =>
        body should include(s"getMe$index")
      }
    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=1010&end=1050&layerName=testLayer&channelIds=$channelOneNodeId&limit=2&offset=2",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val results =
        (parsedBody \ "annotations" \ "results").extract[List[Object]]
      results should have size 2
    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=1010&end=1050&layerName=testLayer&limit=2&offset=2",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val results =
        (parsedBody \ "annotations" \ "results").extract[List[Object]]
      results should have size 2
    }
  }

  test("get annotations starting at epoch if flag is set") {
    val annotations = (0 to 5).map { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range[Long](10 * index + 1000, 10 * index + 1009),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
        .value
    }

    val packageStartTime = TimeSeriesHelper
      .getPackageStartTime(timeseriesPackage, secureContainer)
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=0&end=40&layerName=testLayer&channelIds=$channelOneNodeId&startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val annotationsResponse =
        (parsedBody \ "annotations" \ "results").extract[List[JValue]]
      val expected = annotations
        .take(4)
        .map(TimeSeriesHelper.resetAnnotationStartTime(packageStartTime))
      annotationsResponse.length should be(4)
      expected.map(_.start) should be(Vector(0, 10, 20, 30))
      expected.map(_.end) should be(Vector(9, 19, 29, 39))
    }
  }

  test("get annotations with linked packages")(pending)

  // Window/aggregation tests use streaming Source paths that the fake doesn't
  // implement (production goes through Slick streaming over Postgres). Mark
  // pending.
  test("get annotation counts")(pending)
  test("get annotation counts starting at epoch if flag is set")(pending)
  test("get annotation counts with gaps")(pending)
  test("get annotations via the window function")(pending)
  test("get annotations via the window function with periods merged")(pending)
  test("get all annotations for a time series package")(pending)
  test(
    "get all annotations for a time series package starting at epoch if flag is set"
  )(pending)

  test("delete an annotation") {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(0L, 100L),
        channelIds = SortedSet(channelOneNodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    delete(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      insecureContainer.timeSeriesAnnotationManager
        .getBy(annotation.id)
        .await should not be defined
    }
  }

  test("delete multiple annotations that belong to the same package and layer") {
    val annotationIds = (100 to 200 by 10)
      .sliding(2)
      .map { frame =>
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = timeseriesPackage,
            layerId = layer.id,
            name = "test",
            label = "testLabel",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range(frame(0).toLong, frame(1).toLong),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(frame(0)))
          )(secureContainer.timeSeriesManager)
          .await
          .value
          .id
      }
      .toList

    deleteJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      write(annotationIds),
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[Int] should be(annotationIds.length)
    }
  }

  // ============== Collections =============================================

  test("create an annotation on a Collection package") {
    val request =
      s"""{
         | "name": "testCollectionAnnotation",
         | "channelIds": ["$collectionChannelOneNodeId", "$collectionChannelTwoNodeId"],
         | "label": "collectionLabel",
         | "description": "this is a collection annotation",
         | "start": 1100,
         | "end": 1200
         |}""".stripMargin

    postJson(
      s"/${collectionPackage.nodeId}/layers/${collectionLayer.id}/annotations",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      compactRender(parsedBody \ "label") should include("collectionLabel")
    }

    get(
      s"/${collectionPackage.nodeId}/hasAnnotations",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      assert(body.contains("true"))
    }
  }

  test("get annotations from a Collection package") {
    (0 to 5).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = collectionPackage,
          layerId = collectionLayer.id,
          name = s"collectionAnnotation$index",
          label = "a collection label",
          description = Some("a collection description"),
          userNodeId = loggedInUser.nodeId,
          range = Range[Long](10L * index + 1000, 10 * index + 1009),
          channelIds =
            SortedSet(collectionChannelOneNodeId, collectionChannelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
    }

    get(
      s"/${collectionPackage.nodeId}/layers/${collectionLayer.id}/annotations?start=1025&end=1050&layerName=collectionLayer&channelIds=$collectionChannelOneNodeId",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      (2 to 4).foreach { index =>
        body should include(s"collectionAnnotation$index")
      }
    }
  }

  test("create channels on a Collection package") {
    val request =
      s"""{
         | "name": "newCollectionChannel",
         | "start": 1000,
         | "end": 2000,
         | "unit": "mV",
         | "rate": 1.0,
         | "channelType": "continuous",
         | "lastAnnotation": 1000,
         | "properties": []
         |}""".stripMargin

    postJson(
      s"/${collectionPackage.nodeId}/channels",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(201)
      (parsedBody \ "content" \ "name").extract[String] should be(
        "newCollectionChannel"
      )
    }
  }

  test("get channels from a Collection package") {
    get(
      s"/${collectionPackage.nodeId}/channels",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val channelList = parsedBody.extract[List[Object]]
      channelList should have size 2
    }
  }

  test("create a layer on a Collection package") {
    val request =
      """{
         |  "name": "new collection layer",
         |  "description": "collection layer desc"
         |}""".stripMargin

    postJson(
      s"/${collectionPackage.nodeId}/layers",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val layer = parsedBody.extract[TimeSeriesLayer]
      layer.name should equal("new collection layer")
      layer.description should equal(Some("collection layer desc"))
      layer.timeSeriesId should equal(collectionPackage.nodeId)
    }
  }

  // ============== Layers ==================================================

  test("create a layer") {
    val request =
      """{
         |  "name": "a name",
         |  "description": "desc"
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val l = parsedBody.extract[TimeSeriesLayer]
      l.name should equal("a name")
      l.description should equal(Some("desc"))
      l.timeSeriesId should equal(timeseriesPackage.nodeId)
    }
  }

  test("not create a layer when name is empty") {
    val request =
      """{
         |  "name": "",
         |  "description": "desc"
         |}""".stripMargin

    postJson(
      s"/${timeseriesPackage.nodeId}/layers",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("get all layers for a time series package") {
    val origLayers = (1 to 5).map { i =>
      insecureContainer.layerManager
        .create(timeseriesPackage.nodeId, s"test$i", Some(s"desc$i"))
        .await
    }.toList

    get(
      s"/${timeseriesPackage.nodeId}/layers",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val resp = parsedBody.extract[PagedResponse[TimeSeriesLayer]]
      resp.results should have size 6
      resp.results.sortBy(_.id) should equal(List(layer) ++ origLayers)
    }
  }

  test("get a layer by time series package and layer id") {
    val origLayer = insecureContainer.layerManager
      .create(timeseriesPackage.nodeId, "test", Some("desc"))
      .await

    get(
      s"/${timeseriesPackage.nodeId}/layers/${origLayer.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val l = parsedBody.extract[TimeSeriesLayer]
      l should equal(origLayer)
    }
  }

  test("get a layer by time series package and name") {
    get(
      s"/${timeseriesPackage.nodeId}/layers?name=${layer.name}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val layerResponse = parsedBody.extract[PagedResponse[TimeSeriesLayer]]
      layerResponse.results.head should equal(layer)
    }
  }

  test("update a layer") {
    val request = write(
      LayerRequest(
        name = "updated",
        description = Some("desc"),
        color = Some("#2760FF")
      )
    )

    putJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}",
      request,
      headers = authorizationHeader(loggedInJwt)
    ) {
      val updated = parsedBody.extract[TimeSeriesLayer]
      updated.name should equal("updated")
    }
  }

  test("delete a layer and any annotations that belong to it") {
    (0 to 5).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range(10L * index, 10L * index + 9),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
    }

    delete(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    insecureContainer.layerManager.getBy(layer.id).await should be(None)
  }
}
