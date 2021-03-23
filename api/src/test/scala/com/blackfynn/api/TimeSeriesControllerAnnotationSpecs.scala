// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import com.pennsieve.models.{ Channel, PackageType }
import com.pennsieve.db.TimeSeriesLayer
import com.pennsieve.models.{
  File,
  FileObjectType,
  FileProcessingState,
  FileType,
  Package,
  PackageState
}
import org.json4s.JsonAST.JValue
import com.pennsieve.timeseries.{ AnnotationAggregateWindowResult, Integer }
import com.pennsieve.helpers.TimeSeriesHelper
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.{ Slide, TimeSeries }
import com.pennsieve.test.helpers.EitherValue._
import com.github.tminglei.slickpg.Range
import org.json4s.jackson.Serialization.write
import org.scalatest.OptionValues._
import org.scalatest.{ BeforeAndAfterEach, FlatSpec, Matchers }
import com.pennsieve.dtos.PagedResponse
import com.pennsieve.traits.PostgresProfile.api._

import scala.collection.SortedSet

class TimeSeriesControllerAnnotationSpecs
    extends FlatSpec
    with ApiSuite
    with Matchers
    with BeforeAndAfterEach {

  override def afterAll(): Unit = {
    shutdown(system)
    super.afterAll()
  }

  override def afterStart(): Unit = {
    super.afterStart()

    controller = new TimeSeriesController(
      insecureContainer,
      secureContainerBuilder,
      ec,
      materializer
    )(swagger)
    addServlet(controller, "/*")
  }

  var controller: TimeSeriesController = _
  var timeseriesPackage: Package = _
  var layer: TimeSeriesLayer = _
  var channels: List[Channel] = _
  var channelOneNodeId: String = _
  var channelTwoNodeId: String = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    insecureContainer.dataDB.run(clearDBSchema).await

    val dataset = secureDataSetManager
      .create("timeseries")
      .await
      .value

    timeseriesPackage = packageManager
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

    channels = (1 to 2).map { i =>
      timeSeriesManager
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
  }

  behavior of "Annotations"

  it should "create an annotation" in {

    val request =
      s"""{
        | "name": "testAnnotation",
        | "channelIds": [
        |   "$channelOneNodeId",
        |   "$channelTwoNodeId"
        | ],
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

  it should "create an annotation starting at epoch if flag is set" in {

    val request =
      s"""{
        | "name": "testAnnotation",
        | "channelIds": [
        |   "$channelOneNodeId",
        |   "$channelTwoNodeId"
        | ],
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

      // returned response should have the corrected start/end
      (parsedBody \ "start").extract[Long] should be(100L)
      (parsedBody \ "end").extract[Long] should be(200L)

      val annotationId = (parsedBody \ "id").extract[Int]
      val savedAnnotation = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get

      // the annotation in the DB should have the actual start/end
      savedAnnotation.start should be(1100)
      savedAnnotation.end should be(1200)
    }

    get(
      s"/${timeseriesPackage.nodeId}/hasAnnotations",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      assert(body.contains("true"))
    }
  }

  it should "create an annotation with a linked package" in {
    val slide = packageManager
      .create("a slide", Slide, READY, dataset, Some(loggedInUser.id), None)
      .await
      .value
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": [
         |   "$channelOneNodeId",
         |   "$channelTwoNodeId"
         | ],
         | "label": "newLabel",
         | "description": "this is a description",
         | "start": 1100,
         | "end": 1200,
         | "linkedPackage": "$slide.nodeId"
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

  it should "find no annotations for a package without any annotations" in {

    val anotherTsPackage = packageManager
      .create(
        "testTimeSeries2",
        TimeSeries,
        READY,
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

  it should "create an annotation with data" in {
    val request =
      s"""{
        | "name": "testAnnotation",
        | "channelIds": [
        |   "$channelOneNodeId",
        |   "$channelTwoNodeId"
        | ],
        | "label": "newLabel",
        | "description": "this is a description",
        | "start": 1100,
        | "end": 1200,
        | "data": {
        |   "value": 1,
        |   "type": "Integer"
        | }
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

  it should "check that channel ids belong to this timeseries package" in {
    val request =
      s"""{
         | "name": "testAnnotation",
         | "channelIds": [
         |   "$channelOneNodeId",
         |   "this-is-not-a-real-id"
         | ],
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

  it should "update an annotation" in {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000, 2000),
        channelIds = SortedSet(channelOneNodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    val request =
      s"""{
        | "name": "testAnnotation",
        | "channelIds": [
        |   "$channelOneNodeId",
        |   "$channelTwoNodeId"
        | ],
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

      // returned response should have the correct properties
      (parsedBody \ "start").extract[Long] should be(1100L)
      (parsedBody \ "end").extract[Long] should be(1200L)
      (parsedBody \ "name").extract[String] should be("testAnnotation")
      (parsedBody \ "channelIds")
        .extract[List[String]] should contain theSameElementsAs (List(
        channelOneNodeId,
        channelTwoNodeId
      ))
      (parsedBody \ "label").extract[String] should be("newLabel")
      (parsedBody \ "description").extract[String] should be(
        "this is a description"
      )

      val annotationId = (parsedBody \ "id").extract[Int]
      val savedAnnotation = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get

      // the annotation in the DB should have the correct properties
      savedAnnotation.start should be(1100L)
      savedAnnotation.end should be(1200L)
      savedAnnotation.name should be("testAnnotation")
      savedAnnotation.channelIds should contain theSameElementsAs (List(
        channelOneNodeId,
        channelTwoNodeId
      ))
      savedAnnotation.label should be("newLabel")
      savedAnnotation.description should be(Some("this is a description"))
    }
  }

  it should "update an annotation starting at epoch if flag is set" in {
    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(1000, 2000),
        channelIds = SortedSet(channelOneNodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value

    val request =
      s"""{
        | "name": "testAnnotation",
        | "channelIds": [
        |   "$channelOneNodeId",
        |   "$channelTwoNodeId"
        | ],
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

      // returned response should have the corrected start/end
      (parsedBody \ "start").extract[Long] should be(100L)
      (parsedBody \ "end").extract[Long] should be(200L)

      val annotationId = (parsedBody \ "id").extract[Int]
      val savedAnnotation = insecureContainer.timeSeriesAnnotationManager
        .getBy(annotationId)
        .await
        .get

      // the annotation in the DB should have the actual start/end
      savedAnnotation.start should be(1100)
      savedAnnotation.end should be(1200)
    }
  }

  it should "get an annotation" in {
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

  it should "get an annotation starting at epoch if flag is set" in {
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

    val packageStartTime = TimeSeriesHelper
      .getPackageStartTime(timeseriesPackage, secureContainer)
      .await

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}?startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val start = (parsedBody \ "annotation" \ "start").extract[Long]
      val end = (parsedBody \ "annotation" \ "end").extract[Long]

      val expected = TimeSeriesHelper.resetAnnotationStartTime(
        packageStartTime.right.get
      )(annotation)

      start should be(expected.start)
      end should be(expected.end)
    }
  }

  it should "get an annotation with a linked package" in {

    val slide = packageManager
      .create("a slide", Slide, READY, dataset, Some(loggedInUser.id), None)
      .await
      .value
    val view: File = fileManager
      .create(
        "thumbnail",
        FileType.PNG,
        slide,
        "bucket",
        "thumbnailkey",
        objectType = FileObjectType.View,
        processingState = FileProcessingState.NotProcessable,
        123L
      )
      .await
      .value

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
        None,
        Some(slide.nodeId)
      )(secureContainer.timeSeriesManager)
      .await
      .value

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/${annotation.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      body should include("a label")
      body should include("thumbnailkey")
    }
  }

  it should "get annotations" in {
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

  it should "get annotations starting at epoch if flag is set" in {
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
        .right
        .get
    }

    val packageStartTime = TimeSeriesHelper
      .getPackageStartTime(timeseriesPackage, secureContainer)
      .await
      .right
      .get

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=0&end=40&layerName=testLayer&channelIds=$channelOneNodeId&startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val annotationsResponse =
        (parsedBody \ "annotations" \ "results").extract[List[JValue]]

      val actualStart = annotationsResponse map (a => a \ "start")
      val actualEnd = annotationsResponse map (a => a \ "end")

      val expectedAnnotations = annotations
        .take(4)
        .map(
          TimeSeriesHelper
            .resetAnnotationStartTime(packageStartTime)
        )

      annotationsResponse.length should be(4)

      expectedAnnotations.map(_.start) should be(Vector(0, 10, 20, 30))
      expectedAnnotations.map(_.end) should be(Vector(9, 19, 29, 39))
    }
  }

  it should "get annotations with linked packages" in {

    (0 to 5).foreach { index =>
      val slide = packageManager
        .create(
          s"a slide$index",
          Slide,
          READY,
          dataset,
          Some(loggedInUser.id),
          None
        )
        .await
        .value
      val view: File = fileManager
        .create(
          s"thumbnail$index",
          FileType.PNG,
          slide,
          "bucket",
          "thumbnailkey2",
          objectType = FileObjectType.View,
          processingState = FileProcessingState.NotProcessable,
          123L
        )
        .await
        .value

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
          None,
          Some(slide.nodeId)
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
        body should include("thumbnailkey2")
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
      body should include("thumbnailkey2")

    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations?start=1010&end=1050&layerName=testLayer&limit=2&offset=2",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val results =
        (parsedBody \ "annotations" \ "results").extract[List[Object]]
      results should have size 2
      body should include("thumbnailkey2")

    }
  }

  it should "get annotation counts" in {
    (0 to 20).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range[Long](10 * index + 1000, 10 * index + 1010),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/window?aggregation=count&start=1000&end=1100&layer=testLayer&channels=$channelOneNodeId&period=20",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val counts =
        parsedBody.extract[List[AnnotationAggregateWindowResult[Long]]]
      counts should have length 5
      counts.foreach { count =>
        count.value should equal(2)
      }
    }
  }

  it should "get annotation counts starting at epoch if flag is set" in {
    (0 to 20).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range[Long](10 * index + 1000, 10 * index + 1010),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
    }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/window?aggregation=count&start=0&end=100&layer=testLayer&channels=$channelOneNodeId&period=20&startAtEpoch=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val counts =
        parsedBody.extract[List[AnnotationAggregateWindowResult[Long]]]
      counts should have length 5
      counts.zipWithIndex.foreach {
        case (count, idx) =>
          count.value should equal(2)

          count.start should equal(20 * idx)
          count.end should equal(20 * idx + 20)
      }
    }
  }

  it should "get annotation counts with gaps" in {
    (0 to 100 by 10)
      .sliding(2)
      .foreach { frame =>
        val start = frame(0) + 1000
        val end = frame(0) + 1004
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = timeseriesPackage,
            layerId = layer.id,
            name = "test",
            label = "testLabel",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range[Long](start, end),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(start))
          )(secureContainer.timeSeriesManager)
          .await
      }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/window?aggregation=count&start=1000&end=1100&layer=testLayer&channels=$channelOneNodeId&period=5",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val counts =
        parsedBody.extract[List[AnnotationAggregateWindowResult[Long]]]
      counts should have length 10
      counts.zipWithIndex.forall(_._1.value == 1)
    }
  }

  it should "get annotations via the window function" in {
    (100 to 200 by 10)
      .sliding(2)
      .foreach { frame =>
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = timeseriesPackage,
            layerId = layer.id,
            name = "test",
            label = "testLabel./",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range(frame(0) + 1000, frame(1) + 1000),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(frame(0) + 1000))
          )(secureContainer.timeSeriesManager)
          .await
      }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/window?aggregation=average&start=1100&end=1200&layer=testLayer&channelIds=$channelOneNodeId&period=20",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val windows =
        parsedBody.extract[List[AnnotationAggregateWindowResult[Long]]]
      windows should have length 5
      windows.map(_.value) should equal(List(1105, 1125, 1145, 1165, 1185))
    }
  }

  it should "get annotations via the window function with periods merged" in {
    (0 to 50 by 10)
      .sliding(2)
      .foreach { frame =>
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = timeseriesPackage,
            layerId = layer.id,
            name = "test",
            label = "testLabel",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range(frame(0) + 1000, frame(1) + 1000),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(1))
          )(secureContainer.timeSeriesManager)
          .await
      }
    (70 to 100 by 10)
      .sliding(2)
      .foreach { frame =>
        insecureContainer.timeSeriesAnnotationManager
          .create(
            `package` = timeseriesPackage,
            layerId = layer.id,
            name = "test",
            label = "testLabel",
            description = Some("desc"),
            userNodeId = "userId",
            range = Range(frame(0) + 1000, frame(1) + 1000),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(1))
          )(secureContainer.timeSeriesManager)
          .await
      }

    get(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations/window?aggregation=count&start=1000&end=1100&channelIds=$channelOneNodeId&period=10&mergePeriods=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val windows =
        parsedBody.extract[List[AnnotationAggregateWindowResult[Long]]]
      windows should have length 2
      windows.map(_.value) should equal(List(5, 3))
    }
  }

  it should "get all annotations for a time series package" in {
    val layer2 = insecureContainer.layerManager
      .create(timeseriesPackage.nodeId, "testLayer2", Some("desc2"))
      .await

    (0 to 100 by 10)
      .sliding(2)
      .foreach { frame =>
        List(layer, layer2).foreach { layer =>
          insecureContainer.timeSeriesAnnotationManager
            .create(
              `package` = timeseriesPackage,
              layerId = layer.id,
              name = "test",
              label = "testLabel",
              description = Some("desc"),
              userNodeId = "userId",
              range = Range(frame(0) + 1000, frame(1) + 1000),
              channelIds = SortedSet(channelOneNodeId),
              Some(Integer(1))
            )(secureContainer.timeSeriesManager)
            .await
        }
      }

    get(
      s"/${timeseriesPackage.nodeId}/annotations/window?layerIds=${layer.id}&layerIds=${layer2.id}&aggregation=count&start=1000&end=1100&channelIds=$channelOneNodeId&period=10&mergePeriods=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val windows = parsedBody
        .extract[Map[Long, List[AnnotationAggregateWindowResult[Long]]]]
      windows.size should equal(2)
      windows.get(layer.id).value.map(_.value) should equal(List(10))
      windows.get(layer2.id).value.map(_.value) should equal(List(10))
    }
  }

  it should "get all annotations for a time series package starting at epoch if flag is set" in {
    val layer2 = insecureContainer.layerManager
      .create(timeseriesPackage.nodeId, "testLayer2", Some("desc2"))
      .await

    (0 to 100 by 10)
      .sliding(2)
      .foreach { frame =>
        List(layer, layer2).foreach { layer =>
          insecureContainer.timeSeriesAnnotationManager
            .create(
              `package` = timeseriesPackage,
              layerId = layer.id,
              name = "test",
              label = "testLabel",
              description = Some("desc"),
              userNodeId = "userId",
              range = Range(frame(0), frame(1)),
              channelIds = SortedSet(channelOneNodeId),
              Some(Integer(1))
            )(secureContainer.timeSeriesManager)
            .await
        }
      }

    get(
      s"/${timeseriesPackage.nodeId}/annotations/window?layerIds=${layer.id}&layerIds=${layer2.id}&aggregation=count&start=0&end=100&channelIds=$channelOneNodeId&period=10&mergePeriods=true",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val windows = parsedBody
        .extract[Map[Long, List[AnnotationAggregateWindowResult[Long]]]]
      windows.size should equal(2)
      windows.get(layer.id).value.map(_.value) should equal(List(10))
      windows.get(layer2.id).value.map(_.value) should equal(List(10))
    }
  }

  it should "delete an annotation" in {

    val annotation = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = timeseriesPackage,
        layerId = layer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(0, 100),
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

  it should "delete multiple annotations that belong to the same package and layer" in {
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
            range = Range(frame(0), frame(1)),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(frame(0)))
          )(secureContainer.timeSeriesManager)
          .await
          .value
          .id
      }
      .toList

    val idJson = write(annotationIds)

    deleteJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      idJson,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val response = parsedBody.extract[Int]
      response should be(annotationIds.length)
    }

    val newAnnotationIds = (100 to 200 by 10)
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
            range = Range(frame(0), frame(1)),
            channelIds = SortedSet(channelOneNodeId),
            Some(Integer(frame(0)))
          )(secureContainer.timeSeriesManager)
          .await
          .value
          .id
      }
      .toList

    val badPkg = packageManager
      .create("bad", TimeSeries, READY, dataset, Some(loggedInUser.id), None)
      .await
      .value

    val badChannel = timeSeriesManager
      .createChannel(
        badPkg,
        "badChannel",
        0,
        1000,
        "unit",
        1.0,
        "type",
        None,
        1000
      )
      .await
      .value

    val badLayer =
      insecureContainer.layerManager.create(badPkg.nodeId, "bad").await
    val badAnnotationId = insecureContainer.timeSeriesAnnotationManager
      .create(
        `package` = badPkg,
        layerId = badLayer.id,
        name = "test",
        label = "testLabel",
        description = Some("desc"),
        userNodeId = "userId",
        range = Range(0, 10),
        channelIds = SortedSet(badChannel.nodeId),
        Some(Integer(0))
      )(secureContainer.timeSeriesManager)
      .await
      .value
      .id

    val badIds = newAnnotationIds ++ List(badAnnotationId)
    val badIdJson = write(badIds)

    deleteJson(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}/annotations",
      badIdJson,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      val response = parsedBody.extract[Int]
      response should be(badIds.size - 1)
    }
  }

  behavior of "Layers"

  it should "create a layer" in {
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
      val layer = parsedBody.extract[TimeSeriesLayer]
      layer.name should equal("a name")
      layer.description should equal(Some("desc"))
      layer.timeSeriesId should equal(timeseriesPackage.nodeId)
    }
  }

  it should "not create a layer when name is empty" in {
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

  it should "get all layers for a time series package" in {
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

  it should "get a layer by time series package and layer id" in {
    val origLayer = insecureContainer.layerManager
      .create(timeseriesPackage.nodeId, "test", Some("desc"))
      .await

    get(
      s"/${timeseriesPackage.nodeId}/layers/${origLayer.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val layer = parsedBody.extract[TimeSeriesLayer]
      layer should equal(origLayer)
    }
  }

  it should "get a layer by time series package and name" in {
    get(
      s"/${timeseriesPackage.nodeId}/layers?name=${layer.name}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      val layerResponse = parsedBody.extract[PagedResponse[TimeSeriesLayer]]
      layerResponse.results.head should equal(layer)
    }
  }

  it should "update a layer" in {
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
      val layer = parsedBody.extract[TimeSeriesLayer]
      layer should equal(layer.copy(name = "updated"))
    }
  }

  it should "delete a layer and any annotations that belong to it" in {
    (0 to 5).foreach { index =>
      insecureContainer.timeSeriesAnnotationManager
        .create(
          `package` = timeseriesPackage,
          layerId = layer.id,
          name = s"getMe$index",
          label = "a label",
          description = Some("a description"),
          userNodeId = loggedInUser.nodeId,
          range = Range(10 * index, 10 * index + 9),
          channelIds = SortedSet(channelOneNodeId, channelTwoNodeId),
          None
        )(secureContainer.timeSeriesManager)
        .await
        .value
    }

    delete(
      s"/${timeseriesPackage.nodeId}/layers/${layer.id}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val count = insecureContainer.dataDB
        .run(insecureContainer.timeSeriesAnnotationTableQuery.length.result)
        .await

      count should equal(0)
    }
  }
}
