// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import com.blackfynn.db.{
  CommentsMapper,
  DiscussionsMapper,
  TimeSeriesAnnotation,
  UserMapper
}
import com.blackfynn.models.{
  Annotation,
  Comment,
  Discussion,
  Organization,
  Package,
  User
}
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.domain.{ CoreError, NotFound }
import slick.dbio.{ DBIOAction, NoStream }
import com.blackfynn.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

class DiscussionManager(organization: Organization, db: Database) {

  val discussions = new DiscussionsMapper(organization)
  val comments = new CommentsMapper(organization)

  def run[R](
    action: DBIOAction[R, NoStream, Nothing]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, R] = {
    db.run(action).toEitherT
  }

  def create(
    `package`: Package,
    annotation: Option[Annotation],
    tsAnnotation: Option[TimeSeriesAnnotation] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Discussion] = {
    run {
      val discussion =
        Discussion(`package`.id, annotation.map(_.id), tsAnnotation.map(_.id))
      discussions.returning(discussions) += discussion
    }
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Discussion] =
    db.run {
        discussions
          .filter(_.id === id)
          .result
          .headOption
      }
      .whenNone[CoreError](NotFound(s"Discussion ($id)"))

  def update(
    discussion: Discussion
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Discussion] =
    db.run(discussions.update(discussion)).map(_ => discussion).toEitherT

  def delete(
    discussion: Discussion
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    run {
      discussions
        .filter(_.id === discussion.id)
        .delete
    }

  def createComment(
    message: String,
    user: User,
    discussion: Discussion
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Comment] = {

    val comment = Comment(discussion.id, user.id, message)
    val createTransaction = for {
      commentId <- comments returning comments.map(_.id) += comment
      _ <- discussions.update(discussion)
    } yield comment.copy(id = commentId)

    db.run(createTransaction.transactionally).toEitherT

  }

  def updateComment(
    comment: Comment
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    run {
      comments
        .filter(_.id === comment.id)
        .update(comment)
    }
  }

  def deleteComment(
    comment: Comment
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    run {
      comments
        .filter(_.id === comment.id)
        .delete
    }
  }

  def getComment(
    commentId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Comment] = {
    db.run {
      comments
        .filter(_.id === commentId)
        .result
        .headOption
    }
  }.whenNone[CoreError](NotFound(s"Comment ($commentId)"))

  def findUsersForDiscussions(
    disc: List[Discussion]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {

    val discussionIds: Seq[Int] = disc.map(_.id)

    run {
      UserMapper.filter { user =>
        user.id in comments
          .filter(_.discussionId.inSet(discussionIds))
          .map(_.creatorId)
          .distinct
      }.result
    }
  }

  def find(
    p: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[Discussion, Seq[Comment]]] = {
    run {
      discussions
        .filter(_.packageId === p.id)
        .join(comments)
        .on(_.id === _.discussionId)
        .result
    }.map { items =>
      items
        .groupBy(_._1)
        .mapValues(_.map { case (_, cs) => cs })
    }
  }

}
