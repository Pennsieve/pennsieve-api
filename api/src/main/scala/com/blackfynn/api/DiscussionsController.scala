// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.audit.middleware.Auditor
import com.pennsieve.auth.middleware.DatasetPermission
import com.pennsieve.client.NotificationServiceClient
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{ checkOrErrorT, JwtAuthenticator }
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.dtos.{ Builders, CommentDTO, DiscussionDTO, UserDTO }
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ResultHandlers.{ CreatedResult, OkResult }
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.models.{ Comment, Package, User }
import com.pennsieve.notifications.MessageType.Mention
import com.pennsieve.notifications.{ MentionNotification, NotificationMessage }
import org.scalatra.swagger.Swagger
import org.scalatra.{ ActionResult, AsyncResult, Forbidden, ScalatraServlet }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

case class CreateCommentRequest(
  message: String,
  annotationId: Option[Int],
  timeSeriesAnnotationId: Option[Int],
  packageId: String,
  discussionId: Option[Int],
  mentions: Option[List[String]]
)
case class CommentResponse(comment: CommentDTO, discussion: DiscussionDTO)
case class DiscussionsResponse(
  comments: Map[Int, List[CommentDTO]],
  discussions: List[DiscussionDTO],
  userMap: Option[Map[String, UserDTO]]
)
case class UpdateCommentRequest(message: String)

class DiscussionsController(
  val insecureContainer: InsecureAPIContainer,
  val secureContainerBuilder: SecureContainerBuilderType,
  auditLogger: Auditor,
  notificationServiceClient: NotificationServiceClient,
  asyncExecutor: ExecutionContext
)(implicit
  val swagger: Swagger
) extends ScalatraServlet
    with AuthenticatedController {

  override val swaggerTag: String = "Discussions"

  override protected implicit def executor: ExecutionContext = asyncExecutor

  val getDiscussionOperation = (apiOperation[Option[DiscussionsResponse]](
    "getAnnotationOperation"
  )
    summary "get an annotation"
    parameter pathParam[String]("id").description("the id of the packge"))

  get("/package/:id", operation(getDiscussionOperation)) {
    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        packageId <- paramT[String]("id")
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(packageId)
          .orForbidden
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ViewDiscussionComments))(pkg)
          .coreErrorToActionResult

        comments <- secureContainer.discussionManager.find(pkg).orError
        users <- secureContainer.discussionManager
          .findUsersForDiscussions(comments.keys.toList)
          .orError

        _ <- auditLogger
          .message()
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .append("discussions", comments.toList.map(_._1.id.toString): _*)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult
      } yield {
        val commentMap = comments.map { case (k, v) => (k.id, v.toList) }
        val userIdMap = users.map(u => u.id -> u.nodeId).toMap
        val userNameMap = users
          .map(
            u =>
              u.nodeId -> Builders
                .userDTO(
                  u,
                  organizationNodeId = None,
                  storage = None,
                  pennsieveTermsOfService = None,
                  customTermsOfService = Seq.empty
                )
          )
          .toMap
        val commentedToMap = commentMap.mapValues(
          comments => comments.map(CommentDTO(_, userIdMap))
        )
        val discussionDTOs = comments.keys.toList.map(DiscussionDTO(_))

        DiscussionsResponse(commentedToMap, discussionDTOs, Some(userNameMap))
      }

      val is = result.value.map(OkResult)
    }
  }

  def sendMentionNotifications(
    users: Set[User],
    comment: Comment,
    pkg: Package,
    token: String
  ): NotificationMessage = {
    val notify = MentionNotification(
      users.map(_.id).toList,
      Mention,
      comment.message,
      pkg.nodeId,
      pkg.name
    )
    notificationServiceClient.notify(notify, token)
    notify
  }

  val createCommentOperation = (apiOperation[CommentResponse]("createComment")
    summary "creates a comment and/or a discussion"
    parameter bodyParam[CreateCommentRequest]("createAnnotationRequest"))

  post("/", operation(createCommentOperation)) {
    val req = parsedBody.extract[CreateCommentRequest]

    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        user = secureContainer.user
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetByNodeId(req.packageId)
          .orNotFound
        (pkg, dataset) = packageAndDataset

        _ <- secureContainer.datasetManager
          .assertNotLocked(pkg.datasetId)
          .coreErrorToActionResult
        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ManageDiscussionComments))(
            pkg
          )
          .coreErrorToActionResult

        annotation <- req.annotationId
          .traverse(secureContainer.annotationManager.get(_))
          .orNotFound
        tsannotation <- req.timeSeriesAnnotationId
          .traverse(
            insecureContainer.timeSeriesAnnotationManager
              .getBy(_)
              .whenNone[CoreError](
                NotFound(
                  s"TimeSeriesAnnotation (${req.timeSeriesAnnotationId})"
                )
              )
          )
          .orNotFound
        discussion <- req.discussionId match {
          case Some(discussionId) =>
            secureContainer.discussionManager.get(discussionId).orNotFound
          case None =>
            secureContainer.discussionManager
              .create(pkg, annotation, tsannotation)
              .orError
        }

        token = JwtAuthenticator.generateServiceToken(
          1.minute,
          secureContainer.organization.id,
          Some(pkg.datasetId)
        )

        comment <- secureContainer.discussionManager
          .createComment(req.message, secureContainer.user, discussion)
          .orError
        _ <- req.mentions
          .traverse(uids => {
            secureContainer.userManager
              .getByNodeIds(uids.toSet)
              .map(
                users =>
                  sendMentionNotifications(
                    users.toSet,
                    comment,
                    pkg,
                    token.value
                  )
              )
          })
          .orError

        _ <- auditLogger
          .message()
          .append("discussion-id", discussion.id)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield
        CommentResponse(
          CommentDTO(comment, Map(user.id -> user.nodeId)),
          DiscussionDTO(discussion)
        )

      val is = result.value.map(CreatedResult)
    }
  }

  val deleteCommentOperation = (apiOperation[Int]("deleteComment")
    summary "delete a comment"
    parameter pathParam[String]("commentId")
      .description("the id of the comment")
    parameter pathParam[String]("discussionId")
      .description("the id of the discussion"))

  delete("/:discussionId/comment/:commentId", operation(deleteCommentOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Int] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        discussionId <- paramT[Int]("discussionId")
        commentId <- paramT[Int]("commentId")
        discussion <- secureContainer.discussionManager
          .get(discussionId)
          .orNotFound
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(discussion.packageId)
          .orForbidden
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer.datasetManager
          .assertNotLocked(pkg.datasetId)
          .coreErrorToActionResult
        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ManageDiscussionComments))(
            pkg
          )
          .coreErrorToActionResult

        comment <- secureContainer.discussionManager
          .getComment(commentId)
          .orNotFound

        _ <- checkOrErrorT(
          comment.creatorId == secureContainer.user.id || secureContainer.user.isSuperAdmin
        )(Forbidden("not your comment!"))

        _ <- auditLogger
          .message()
          .append("discussion", discussionId.toString)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

        deleted <- secureContainer.discussionManager
          .deleteComment(comment)
          .orError
      } yield deleted

      override val is = result.value.map(OkResult)
    }
  }

  val deleteDiscussionOperation = (apiOperation[Int]("deleteDiscussion")
    summary "delete a discussion"
    parameter pathParam[String]("discussionId")
      .description("the id of the discussion"))

  delete("/:discussionId", operation(deleteDiscussionOperation)) {

    new AsyncResult {
      val result: EitherT[Future, ActionResult, Int] = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        discussionId <- paramT[Int]("discussionId")

        discussion <- secureContainer.discussionManager
          .get(discussionId)
          .orNotFound
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(discussion.packageId)
          .orForbidden
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer.datasetManager
          .assertNotLocked(pkg.datasetId)
          .coreErrorToActionResult
        _ <- secureContainer
          .authorizePackageId(Set(DatasetPermission.ManageDiscussionComments))(
            discussion.packageId
          )
          .coreErrorToActionResult

        deleted <- secureContainer.discussionManager.delete(discussion).orError

        _ <- auditLogger
          .message()
          .append("discussion", discussionId.toString)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult

      } yield deleted
      override val is = result.value.map(OkResult)
    }
  }

  val updateCommentOperation = (apiOperation[CommentResponse]("updateComment")
    summary "updates an comment"
    parameter bodyParam[UpdateCommentRequest]("body")
      .description("the comment to add")
    parameter pathParam[String]("discussionId")
      .description("the id of the discussion")
    parameter pathParam[String]("commentId")
      .description("the id of the comment"))

  put("/:discussionId/comment/:commentId", operation(updateCommentOperation)) {
    val req = parsedBody.extract[UpdateCommentRequest]

    new AsyncResult {
      val result = for {
        secureContainer <- getSecureContainer
        traceId <- getTraceId(request)
        user = secureContainer.user
        discussionId <- paramT[Int]("discussionId")
        commentId <- paramT[Int]("commentId")
        discussion <- secureContainer.discussionManager
          .get(discussionId)
          .orNotFound
        packageAndDataset <- secureContainer.packageManager
          .getPackageAndDatasetById(discussion.packageId)
          .orForbidden
        (pkg, dataset) = packageAndDataset
        _ <- secureContainer.datasetManager
          .assertNotLocked(pkg.datasetId)
          .coreErrorToActionResult
        _ <- secureContainer
          .authorizePackage(Set(DatasetPermission.ManageDiscussionComments))(
            pkg
          )
          .coreErrorToActionResult

        comment <- secureContainer.discussionManager
          .getComment(commentId)
          .orNotFound
        _ <- checkOrErrorT(
          comment.creatorId == secureContainer.user.id || secureContainer.user.isSuperAdmin
        )(Forbidden("not your comment!"))
        _ <- secureContainer.discussionManager
          .updateComment(comment.copy(message = req.message))
          .orError
        _ <- auditLogger
          .message()
          .append("discussion", discussionId.toString)
          .append("dataset-id", dataset.id)
          .append("dataset-node-id", dataset.nodeId)
          .append("package-id", pkg.id)
          .append("package-node-id", pkg.nodeId)
          .log(traceId)
          .toEitherT
          .coreErrorToActionResult
      } yield
        CommentResponse(
          CommentDTO(comment, Map(user.id -> user.nodeId)),
          DiscussionDTO(discussion)
        )

      override val is = result.value.map(OkResult)
    }
  }

}
