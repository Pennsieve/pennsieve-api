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

import com.pennsieve.models.{ Dataset, Package }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

/**
  * DEPRECATED: use ExtendedPackageDTO for new endpoints
  */
case class PackageDTO(
  content: WrappedPackage,
  properties: List[ModelPropertiesDTO],
  parent: Option[PackageDTO],
  objects: Option[Map[String, List[FileDTO]]],
  children: List[PackageDTO],
  ancestors: Option[List[PackageDTO]],
  channels: Option[List[ChannelDTO]] = None,
  externalFile: Option[ExternalFileDTO] = None,
  storage: Option[Long] = None,
  extension: Option[String] = None
)

case class ExtendedPackageDTO(
  content: PackageContent,
  properties: List[ModelPropertiesDTO],
  parent: Option[ExtendedPackageDTO],
  objects: Option[Map[String, List[SimpleFileDTO]]],
  children: List[ExtendedPackageDTO],
  ancestors: Option[List[ExtendedPackageDTO]],
  storage: Option[Long],
  channels: Option[List[ChannelDTO]] = None,
  externalFile: Option[ExternalFileDTO] = None,
  isTruncated: Option[Boolean] = None,
  extension: Option[String] = None
)

/**
  * DEPRECATED: use ExtendedPackageDTO for new endpoints
  */
object PackageDTO {

  implicit val encoder: Encoder[PackageDTO] = deriveEncoder[PackageDTO]
  implicit val decoder: Decoder[PackageDTO] = deriveDecoder[PackageDTO]

  /*
   * This PackageDTO constructor should make no subsequent database calls
   * It constructs a PackageDTO with only the information relevant to the
   * top-level package and none of its related packages
   */
  def simple(
    `package`: Package,
    dataset: Dataset,
    storage: Option[Long] = None,
    externalFile: Option[ExternalFileDTO] = None,
    withExtension: Option[String] = None
  ): PackageDTO = {
    val properties =
      ModelPropertiesDTO.fromModelProperties(`package`.attributes)

    PackageDTO(
      content = WrappedPackage(`package`, dataset),
      properties = properties,
      parent = None,
      objects = None,
      children = List.empty[PackageDTO],
      ancestors = None,
      channels = None,
      externalFile = externalFile,
      storage = storage,
      extension = withExtension
    )
  }
}

object ExtendedPackageDTO {

  implicit val encoder: Encoder[ExtendedPackageDTO] =
    deriveEncoder[ExtendedPackageDTO]
  implicit val decoder: Decoder[ExtendedPackageDTO] =
    deriveDecoder[ExtendedPackageDTO]

  /*
   * This ExtendedPackageDTO constructor should make no subsequent database calls
   * It constructs a PackageDTO with only the information relevant to the
   * top-level package and none of its related packages
   */
  def simple(
    `package`: Package,
    dataset: Dataset,
    storage: Option[Long] = None,
    externalFile: Option[ExternalFileDTO] = None,
    objects: Option[Map[String, List[SimpleFileDTO]]] = None,
    isTruncated: Option[Boolean] = None,
    withExtension: Option[String] = None
  ): ExtendedPackageDTO = {
    val properties =
      ModelPropertiesDTO.fromModelProperties(`package`.attributes)

    ExtendedPackageDTO(
      content = PackageContent(`package`, dataset),
      properties = properties,
      parent = None,
      objects = objects,
      children = List.empty[ExtendedPackageDTO],
      ancestors = None,
      storage = storage,
      externalFile = externalFile,
      isTruncated = isTruncated,
      extension = withExtension
    )
  }
}
