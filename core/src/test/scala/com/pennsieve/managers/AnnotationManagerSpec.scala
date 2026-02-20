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

import com.pennsieve.models.{
  Annotation,
  AnnotationLayer,
  ModelProperty,
  Package,
  PathElement
}
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.PDF
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class AnnotationManagerSpec extends BaseManagerSpec {

  var annotationMgr: AnnotationManager = _
  var packageMgr: PackageManager = _
  var testPackage: Package = _
  var layer: AnnotationLayer = _
  var annotation: Annotation = _

  val nums = (1 to 10).toList.map(_.toDouble)
  val props = List(ModelProperty("mykey", "myvalue", "string", "category"))
  val path = List(PathElement("elementtype", nums))

  override def beforeEach(): Unit = {
    super.beforeEach()
    annotationMgr = annotationManager(testOrganization)
    packageMgr = packageManager(testOrganization, superAdmin)
    testDataset = createDataset()
    testPackage = packageMgr
      .create("test package", PDF, READY, testDataset, Some(1), None)
      .await
      .value
    layer = annotationMgr
      .createLayer(testPackage, "test layer", "green")
      .await
      .value

    annotation = annotationMgr
      .create(superAdmin, layer, "here's a thing!", path, props)
      .await
      .value
  }

  "getting an annotation" should "fetch it" in {

    val fetched = annotationMgr.find(testPackage).await.value.map {
      case (k, v) => (k.id -> v)
    }

    assert(fetched.keys.toList.contains(layer.id))

    val gotAnnotation = fetched.get(layer.id).get.head

    assert(gotAnnotation.description == "here's a thing!")
    assert(gotAnnotation.path.head.elementType == "elementtype")
    assert(gotAnnotation.path.head.data == nums)
    assert(gotAnnotation.attributes.head.key == "mykey")
  }

  "updating an annotation's comment" should "update it" in {
    annotationMgr
      .update(annotation.copy(description = "updated"))
      .await
      .value
    val updated = annotationMgr.get(annotation.id).await.value
    assert(updated.description == "updated")
  }

  "removing an annotation" should "remove it but not all annotations for the pkg" in {
    val user = createUser()

    val annotation2 =
      annotationMgr.create(user, layer, "and another thing").await.value

    val amap: Map[Int, Seq[Annotation]] =
      annotationMgr.find(testPackage).await.value.map {
        case (k, v) => (k.id -> v)
      }

    assert(amap.get(layer.id).get.length == 2)
    annotationMgr.delete(annotation2).await

    val amap2: Map[Int, Seq[Annotation]] =
      annotationMgr.find(testPackage).await.value.map {
        case (k, v) => (k.id -> v)
      }

    assert(amap2.get(layer.id).get.length == 1)

  }

  "deleting an annotation layer" should "remove it" in {
    val todelete = annotationMgr
      .createLayer(testPackage, "doomed test layer", "green")
      .await
      .value
    assert(annotationMgr.getLayer(todelete.id).await.isRight)
    annotationMgr.deleteLayer(todelete).await
    assert(annotationMgr.getLayer(todelete.id).await.isLeft)
  }

}
