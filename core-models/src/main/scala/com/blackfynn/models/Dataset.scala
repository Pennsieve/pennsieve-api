// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

import enumeratum.EnumEntry.{ Snakecase, UpperSnakecase }
import enumeratum._

import java.util.UUID
import scala.collection.immutable.IndexedSeq

case class DatasetNodeId(value: String) extends AnyVal

sealed trait DatasetType extends EnumEntry with Snakecase

object DatasetType extends Enum[DatasetType] with CirceEnum[DatasetType] {
  val values: IndexedSeq[DatasetType] = findValues

  case object Research extends DatasetType
  case object Trial extends DatasetType
}

final case class Dataset(
  nodeId: String,
  name: String,
  state: DatasetState = DatasetState.READY,
  description: Option[String] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  // the permission property dictates what permission the dataset has with the organization
  // which owns it. If it's null/None then there are no organization wide permission otherwise
  // that permission applies to everyone in the organization
  permission: Option[DBPermission] = None,
  role: Option[Role] = None,
  automaticallyProcessPackages: Boolean = false,
  statusId: Int,
  publicationStatusId: Option[Int] = None,
  `type`: DatasetType = DatasetType.Research,
  license: Option[License] = None,
  tags: List[String] = List.empty,
  bannerId: Option[UUID] = None,
  readmeId: Option[UUID] = None,
  dataUseAgreementId: Option[Int] = None,
  etag: ETag = ETag.touch,
  id: Int = 0
) {

  def typedNodeId: DatasetNodeId = DatasetNodeId(nodeId)
}
