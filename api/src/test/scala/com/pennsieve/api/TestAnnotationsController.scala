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

import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.domain.CoreError
import com.pennsieve.dtos.ModelPropertyRO
import com.pennsieve.helpers.MockAuditLogger
import com.pennsieve.models.{
  Annotation,
  AnnotationLayer,
  ModelProperty,
  PathElement
}
import org.json4s.jackson.Serialization.write
import org.scalatest.EitherValues._

class TestAnnotationsController extends BaseApiTest {

  val auditLogger: Auditor = new MockAuditLogger()

  override def afterStart(): Unit = {
    super.afterStart()
    addServlet(
      new AnnotationsController(
        insecureContainer,
        secureContainerBuilder,
        system.dispatcher,
        auditLogger
      ),
      "/*"
    )
  }

  test("swagger") {
    import com.pennsieve.web.ResourcesApp
    addServlet(new ResourcesApp, "/api-docs/*")

    get("/api-docs/swagger.json") {
      status should equal(200)
      println(body)
    }
  }

  test("create an annotation layer") {

    val createReq =
      write(CreateLayerRequest("test layer", personal.nodeId, None))
    postJson(
      s"/layer",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      body should include("test layer")
    }

    val anns: Map[AnnotationLayer, Seq[Annotation]] =
      annotationManager.find(personal).await.right.value

    //we should find one empty layer
    assert(anns.values.toList.contains(Seq()))
  }

  test("update an annotation layer") {

    val oldLayer = annotationManager
      .createLayer(personal, "test layer", "autumn embers")
      .await
      .right
      .value

    val updateRequest =
      write(UpdateLayerRequest("test layer updated", Some("red")))

    putJson(
      s"/layer/${oldLayer.id}",
      updateRequest,
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)

      val updatedLayer =
        annotationManager.getLayer(oldLayer.id).await.right.value

      assert(updatedLayer.color == "red")
      assert(updatedLayer.name == "test layer updated")

    }
  }

  test("delete an annotation layer") {
    val deleteme = annotationManager
      .createLayer(personal, "doomed layer", "black")
      .await
      .right
      .value

    delete(s"/layer/${deleteme.id}", headers = authorizationHeader(loggedInJwt)) {
      status should equal(200)
      val deletedLayer = annotationManager.getLayer(deleteme.id).await
      assert(deletedLayer.isLeft)
    }

  }

  test("create annotation on thing") {

    val props = List(
      ModelPropertyRO("Owner", loggedInUser.nodeId, None, None, None, None),
      ModelPropertyRO("Key", "Value1", None, None, None, None),
      ModelPropertyRO("Key", "Value2", None, Some("Category"), None, None)
    )
    val createReq =
      write(CreateAnnotationRequest("Boom", personal.nodeId, None, props))

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      body should include("Boom")
      body should include("Owner")
      body should include("Value1")
      body should include("Value2")
    }
  }

  test(
    "creating an annotation on a layer that does not belong to the package should fail"
  ) {

    val testLayer = annotationManager
      .createLayer(home, "test layer", "autumn embers")
      .await
      .right
      .value

    val props = List(
      ModelPropertyRO("Owner", loggedInUser.nodeId, None, None, None, None),
      ModelPropertyRO("Key", "Value1", None, None, None, None),
      ModelPropertyRO("Key", "Value2", None, Some("Category"), None, None)
    )

    val createReq = write(
      CreateAnnotationRequest(
        "Boom",
        personal.nodeId,
        Some(testLayer.id),
        props
      )
    )

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(404)
    }
  }

  test("get annotation") {
    val props = List(
      new ModelProperty("Owner", loggedInUser.nodeId),
      new ModelProperty("Key", "Value1"),
      new ModelProperty("Key", "Value2", "Category")
    )
    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, props)
      .await
      .right
      .value

    get(
      s"/${annotation.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("this is the thing")
    }
  }

  test("update annotation") {
    val props = List(
      new ModelProperty("Owner", loggedInUser.nodeId),
      new ModelProperty("Key", "Value1"),
      new ModelProperty("Key", "Value2", "Category")
    )
    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val layer2 = annotationManager
      .createLayer(home, "the other layer", "blue")
      .await
      .right
      .value

    val annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, props)
      .await
      .right
      .value

    putJson(
      s"/${annotation.id}",
      write(UpdateAnnotationRequest("not the thing you're looking for", None)),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("not the thing you're looking for")
      val ann = annotationManager.get(annotation.id).await.right.value
      assert(ann.description == "not the thing you're looking for")
      assert(ann.layerId == layer.id)
    }

    putJson(
      s"/${annotation.id}",
      write(
        UpdateAnnotationRequest("another annotation update", Some(layer2.id))
      ),
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      body should include("another annotation update")
      val ann = annotationManager.get(annotation.id).await.right.value
      assert(ann.description == "another annotation update")
      assert(ann.layerId == layer2.id)
    }

  }

  test("delete annotation") {

    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val _annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, Nil)
      .await
      .right
      .value

    delete(
      s"/${_annotation.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val no: Either[CoreError, Annotation] =
        annotationManager.get(_annotation.id).await
      assert(no.isLeft)
    }
  }

  test("delete annotation with discussion should fail") {

    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val _annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, Nil)
      .await
      .right
      .value
    val discussion =
      discussionManager.create(home, Some(_annotation)).await.right.value
    discussionManager.createComment("hey there", loggedInUser, discussion).await

    delete(
      s"/${_annotation.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(400)
    }

    delete(
      s"/${_annotation.id}?withDiscussions=true",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
    }

    assert(annotationManager.get(_annotation.id).await.isLeft)
    assert(discussionManager.get(discussion.id).await.isLeft)

  }

}
