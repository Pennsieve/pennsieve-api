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
  changelogId: Option[UUID] = None,
  dataUseAgreementId: Option[Int] = None,
  etag: ETag = ETag.touch,
  id: Int = 0
) {

  def typedNodeId: DatasetNodeId = DatasetNodeId(nodeId)
}
