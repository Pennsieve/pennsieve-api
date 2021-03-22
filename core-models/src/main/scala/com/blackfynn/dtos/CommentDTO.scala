// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import java.time.ZonedDateTime

import com.blackfynn.models.Comment

case class CommentDTO(
  discussion_id: Int,
  creator_id: String,
  message: String,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  id: Int
)

object CommentDTO {
  def apply(comment: Comment, idmap: Map[Int, String]): CommentDTO = {
    new CommentDTO(
      comment.discussionId,
      idmap.getOrElse(comment.creatorId, ""),
      comment.message,
      comment.createdAt,
      comment.updatedAt,
      comment.id
    )
  }
}
