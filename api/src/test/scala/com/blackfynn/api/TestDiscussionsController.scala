// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import com.blackfynn.helpers.MockAuditLogger
import com.blackfynn.models.{ Annotation, Comment, Discussion }
import com.blackfynn.notifications.MockNotificationServiceClient
import org.apache.http.impl.client.HttpClients
import org.scalatest.EitherValues._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write

class TestDiscussionsController extends BaseApiTest {

  var discussion: Discussion = _
  var comment: Comment = _

  override def afterStart(): Unit = {
    super.afterStart()
    val httpClient = HttpClients.createMinimal()
    addServlet(
      new DiscussionsController(
        insecureContainer,
        secureContainerBuilder,
        new MockAuditLogger(),
        new MockNotificationServiceClient,
        system.dispatcher
      ),
      "/*"
    )
  }

  override def beforeEach() {
    super.beforeEach()
    val discussionMgr = secureContainer.discussionManager

    discussion = discussionMgr.create(personal, None).await.right.value

    discussionMgr.createComment("hello!", loggedInUser, discussion).await
    discussionMgr.createComment("how are you?", loggedInUser, discussion).await
    comment = discussionMgr
      .createComment("Fine, thanks.", loggedInUser, discussion)
      .await
      .right
      .value
  }

  test("create a discussion about a thing") {
    val createReq = write(
      CreateCommentRequest(
        "this is my home folder",
        None,
        None,
        personal.nodeId,
        None,
        None
      )
    )

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)

      val json = parse(response.body)
      compact(render(json \ "discussion" \ "package_id")) should include(
        s"${personal.id}"
      )
      compact(render(json \ "comment" \ "creator_id")) should include(
        s"${loggedInUser.nodeId}"
      )
      compact(render(json \ "comment" \ "message")) should include(
        "this is my home folder"
      )
    }
  }

  test("create a discussion about an annotation on a thing") {
    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, Nil)
      .await
      .right
      .value
    val createReq = write(
      CreateCommentRequest(
        "this is my home folder",
        Some(annotation.id),
        None,
        personal.nodeId,
        None,
        None
      )
    )

    postJson(
      s"",
      createReq,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(201)
      val json = parse(response.body)
      compact(render(json \ "discussion" \ "package_id")) should include(
        s"${personal.id}"
      )
      compact(render(json \ "comment" \ "creator_id")) should include(
        s"${loggedInUser.nodeId}"
      )
      compact(render(json \ "comment" \ "message")) should include(
        "this is my home folder"
      )
      compact(render(json \ "discussion" \ "annotation_id")) should include(
        s"${annotation.id}"
      )
    }
  }

  test("delete a discussion about an annotation") {

    val layer = annotationManager
      .createLayer(home, "home folder", "red")
      .await
      .right
      .value
    val annotation = annotationManager
      .create(loggedInUser, layer, "this is the thing", Nil, Nil)
      .await
      .right
      .value
    val discussion =
      discussionManager.create(home, Some(annotation)).await.right.value

    delete(
      s"/${discussion.id}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      val fetch = discussionManager.get(discussion.id).await
      status should equal(200)
      assert(fetch.isLeft)
    }
  }

  test("get discussion") {

    get(
      s"/package/${personal.nodeId}",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      assert(body.contains("Fine, thanks"))
      assert(body.contains(loggedInUser.nodeId))
    }
  }

  test("update comment") {

    val newmsg = "Just fine thankyou."
    val req = write(UpdateCommentRequest(newmsg))

    putJson(
      s"${discussion.id}/comment/${comment.id}",
      req,
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {

      status should equal(200)
      val upd = secureContainer.discussionManager
        .getComment(comment.id)
        .await
        .right
        .value

      assert(upd.message == newmsg)

    }
  }

  test("delete a comment") {

    deleteJson(
      s"${discussion.id}/comment/${comment.id}",
      "",
      headers = authorizationHeader(loggedInJwt) ++ traceIdHeader()
    ) {
      status should equal(200)
      val fetched =
        secureContainer.discussionManager.getComment(comment.id).await
      assert(fetched.isLeft)
    }
  }

}
