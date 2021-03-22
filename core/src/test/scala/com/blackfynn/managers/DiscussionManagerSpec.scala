// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import com.blackfynn.models.PackageType
import com.blackfynn.db.TimeSeriesAnnotation
import com.blackfynn.models.PackageState.READY
import com.blackfynn.models.PackageType.PDF
import com.blackfynn.models.{
  Annotation,
  AnnotationLayer,
  Comment,
  Discussion,
  ModelProperty,
  Package,
  PathElement
}
import com.blackfynn.test.helpers.EitherValue._
import org.scalatest.EitherValues._
import com.github.tminglei.slickpg.Range

import scala.collection.SortedSet
import scala.concurrent.ExecutionContext.Implicits.global

class DiscussionManagerSpec extends BaseManagerSpec {

  var annotationManager: AnnotationManager = _
  var tsManager: TimeSeriesManager = _
  var tsAnnotationManager: TimeSeriesAnnotationManager = _
  var tsLayerManager: TimeSeriesLayerManager = _
  var discussionManager: DiscussionManager = _
  var packageManager: PackageManager = _
  var testPackage: Package = _
  var testPackage2: Package = _
  var layer: AnnotationLayer = _
  var annotation: Annotation = _
  var discussion: Discussion = _
  var discussionTs: Discussion = _
  var tsAnnotation: TimeSeriesAnnotation = _

  val nums = (1 to 10).toList.map(_.toDouble)
  val props = List(ModelProperty("mykey", "myvalue", "string", "category"))
  val path = List(PathElement("elementtype", nums))

  override def beforeEach() {
    super.beforeEach()
    annotationManager = annotationManager(testOrganization)
    discussionManager = discussionManager(testOrganization)
    tsManager = timeSeriesManager()
    tsAnnotationManager = timeSeriesAnnotationManager()
    tsLayerManager = timeSeriesLayerManager()

    packageManager = packageManager(testOrganization, superAdmin)
    testDataset = createDataset()
    testPackage = packageManager
      .create(
        "test package",
        PDF,
        READY,
        testDataset,
        Some(superAdmin.id),
        None
      )
      .await
      .right
      .value
    testPackage2 = packageManager
      .create(
        "test package2",
        PackageType.TimeSeries,
        READY,
        testDataset,
        Some(superAdmin.id),
        None
      )
      .await
      .right
      .value
    layer = annotationManager
      .createLayer(testPackage, "test layer", "green")
      .await
      .right
      .value
    annotation = annotationManager
      .create(superAdmin, layer, "here's a thing!", path, props)
      .await
      .right
      .value

    discussion =
      discussionManager.create(testPackage, Some(annotation)).await.right.value
    discussionManager.createComment("hello!", superAdmin, discussion).await
    discussionManager
      .createComment("how are you?", superAdmin, discussion)
      .await
    discussionManager
      .createComment("Fine, thanks.", superAdmin, discussion)
      .await

    val tsChannel = tsManager
      .createChannel(
        testPackage2,
        "Test Channel",
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
    val tsLayer = tsLayerManager
      .create(testPackage2.nodeId, "tslayer", None, Some("green"))
      .await
    tsAnnotation = tsAnnotationManager
      .create(
        testPackage2,
        tsLayer.id,
        "seizure starts here",
        "label",
        Some("description"),
        superAdmin.nodeId,
        Range[Long](5, 200),
        SortedSet(tsChannel.nodeId),
        None
      )(tsManager)
      .await
      .right
      .value

    discussionTs = discussionManager
      .create(testPackage2, None, Some(tsAnnotation))
      .await
      .right
      .value
    discussionManager
      .createComment(
        "so, let's talk about timeseries",
        superAdmin,
        discussionTs
      )
      .await

  }

  "getting an discussion map" should "find all comments" in {
    val discussionMap: List[(Discussion, Seq[Comment])] =
      discussionManager.find(testPackage).await.right.value.toList

    val discn = discussionMap.head._1
    assert(discn.annotationId == Some(annotation.id))
    assert(discn.packageId == testPackage.id)

    val comments = discussionMap.head._2

    assert(comments.length == 3)
    assert(comments(0).message == "hello!")

  }

  "adding a comment" should "update the discussion" in {
    val oldUpdatedAt = discussion.updatedAt
    val newComment = discussionManager
      .createComment("This is a test!", superAdmin, discussion)
      .await
      .right
      .value
    val newUpdatedAt =
      discussionManager.get(newComment.discussionId).await.right.value.updatedAt
    assert(oldUpdatedAt.isBefore(newUpdatedAt))
  }

  "timeseries annotation ids" should "be persisted" in {
    val tsDiscussion = discussionManager.get(discussionTs.id).await.right.value
    assert(tsDiscussion.timeSeriesAnnotationId == Some(tsAnnotation.id))
  }

}
