// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import com.blackfynn.models.{ Dataset, Package }
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
