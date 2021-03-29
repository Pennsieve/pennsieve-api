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

package com.pennsieve.dtos

import java.time.ZonedDateTime

import com.pennsieve.models.{
  Dataset,
  ExternalFile,
  Package,
  PackageState,
  PackageType
}
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

/**
  * DEPRECATED: use PackageContent for new endpoints
  */
case class WrappedPackage(
  id: String,
  nodeId: String, // added nodeId field to aid migration to PackageContent model
  name: String,
  packageType: PackageType,
  datasetId: String,
  datasetNodeId: String,
  ownerId: Option[Int],
  state: PackageState,
  parentId: Option[Int],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  intId: Int,
  datasetIntId: Int
)

case class PackageContent(
  id: Int,
  name: String,
  packageType: PackageType,
  datasetId: Int,
  datasetNodeId: String,
  ownerId: Option[Int],
  state: PackageState,
  parentId: Option[Int],
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  nodeId: String
)

/**
  * DEPRECATED: use PackageContent for new endpoints
  */
object WrappedPackage {
  import io.circe.java8.time._

  implicit val encoder: Encoder[WrappedPackage] = deriveEncoder[WrappedPackage]
  implicit val decoder: Decoder[WrappedPackage] = deriveDecoder[WrappedPackage]

  def apply(`package`: Package, dataset: Dataset): WrappedPackage = {
    WrappedPackage(
      id = `package`.nodeId,
      nodeId = `package`.nodeId,
      name = `package`.name,
      packageType = `package`.`type`,
      datasetId = dataset.nodeId,
      datasetNodeId = dataset.nodeId,
      ownerId = `package`.ownerId,
      state = `package`.state.asDTO,
      parentId = `package`.parentId,
      createdAt = `package`.createdAt,
      updatedAt = `package`.updatedAt,
      intId = `package`.id,
      datasetIntId = dataset.id
    )
  }
}

object PackageContent {
  import io.circe.java8.time._

  implicit val encoder: Encoder[PackageContent] = deriveEncoder[PackageContent]
  implicit val decoder: Decoder[PackageContent] = deriveDecoder[PackageContent]

  def apply(`package`: Package, dataset: Dataset): PackageContent = {
    PackageContent(
      id = `package`.id,
      nodeId = `package`.nodeId,
      name = `package`.name,
      packageType = `package`.`type`,
      datasetId = dataset.id,
      datasetNodeId = dataset.nodeId,
      ownerId = `package`.ownerId,
      state = `package`.state.asDTO,
      parentId = `package`.parentId,
      createdAt = `package`.createdAt,
      updatedAt = `package`.updatedAt
    )
  }
}
