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

// Copyright (c) 2019 The University of Pennsylvania. All Rights Reserved.

package com.pennsieve.models

import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import org.apache.commons.io.comparator.NameFileComparator
import java.time.LocalDate

import cats.syntax.functor._
import org.apache.commons.io.FilenameUtils
import io.circe.{ Decoder, DecodingFailure, Encoder, HCursor }

import java.util.UUID

sealed trait DatasetMetadata {
  val files: List[FileManifest]
}

case class DatasetMetadataV1_0(
  pennsieveDatasetId: Int,
  version: Int,
  name: String,
  description: String,
  creator: String,
  contributors: List[String],
  sourceOrganization: String,
  keywords: List[String],
  datePublished: LocalDate,
  license: Option[License],
  `@id`: String, // DOI
  publisher: String = "The University of Pennsylvania",
  `@context`: String = "http://schema.org/",
  `@type`: String = "Dataset",
  schemaVersion: String = "http://schema.org/version/3.7/",
  files: List[FileManifest] = List.empty,
  pennsieveSchemaVersion: Int = 1
) extends DatasetMetadata

case class DatasetMetadataV2_0(
  pennsieveDatasetId: Int,
  version: Int,
  name: String,
  description: String,
  creator: String,
  contributors: List[PublishedContributor],
  sourceOrganization: String,
  keywords: List[String],
  datePublished: LocalDate,
  license: Option[License],
  `@id`: String, // DOI
  publisher: String = "The University of Pennsylvania",
  `@context`: String = "http://schema.org/",
  `@type`: String = "Dataset",
  schemaVersion: String = "http://schema.org/version/3.7/",
  files: List[FileManifest] = List.empty,
  pennsieveSchemaVersion: Int = 2
) extends DatasetMetadata

/**
  * Dataset metadata to be encoded as JSON-LD.
  * See https://schema.org/Dataset for schema details.
  */
case class DatasetMetadataV3_0(
  pennsieveDatasetId: Int,
  version: Int,
  name: String,
  description: String,
  creator: PublishedContributor,
  contributors: List[PublishedContributor],
  sourceOrganization: String,
  keywords: List[String],
  datePublished: LocalDate,
  license: Option[License],
  `@id`: String, // DOI
  publisher: String = "The University of Pennsylvania",
  `@context`: String = "http://schema.org/",
  `@type`: String = "Dataset",
  schemaVersion: String = "http://schema.org/version/3.7/",
  files: List[FileManifest] = List.empty,
  pennsieveSchemaVersion: String = "3.0"
) extends DatasetMetadata

case class DatasetMetadataV4_0(
  pennsieveDatasetId: Int,
  version: Int,
  revision: Option[Int],
  name: String,
  description: String,
  creator: PublishedContributor,
  contributors: List[PublishedContributor],
  sourceOrganization: String,
  keywords: List[String],
  datePublished: LocalDate,
  license: Option[License],
  `@id`: String, // DOI
  publisher: String = "The University of Pennsylvania",
  `@context`: String = "http://schema.org/",
  `@type`: String = "Dataset",
  schemaVersion: String = "http://schema.org/version/3.7/",
  collections: Option[List[PublishedCollection]] = None,
  relatedPublications: Option[List[PublishedExternalPublication]] = None,
  files: List[FileManifest] = List.empty,
  pennsieveSchemaVersion: String = "4.0"
) extends DatasetMetadata

case class DatasetMetadataV5_0(
  pennsieveDatasetId: Int,
  version: Int,
  revision: Option[Int],
  name: String,
  description: String,
  creator: PublishedContributor,
  contributors: List[PublishedContributor],
  sourceOrganization: String,
  keywords: List[String],
  datePublished: LocalDate,
  license: Option[License],
  `@id`: String, // DOI
  publisher: String = "The University of Pennsylvania",
  `@context`: String = "http://schema.org/",
  `@type`: String = "Dataset",
  schemaVersion: String = "http://schema.org/version/3.7/",
  collections: Option[List[PublishedCollection]] = None,
  relatedPublications: Option[List[PublishedExternalPublication]] = None,
  files: List[FileManifest] = List.empty,
  pennsieveSchemaVersion: String = "4.0"
) extends DatasetMetadata

object DatasetMetadataV4_0 {
  implicit val encoder: Encoder[DatasetMetadataV4_0] =
    deriveEncoder[DatasetMetadataV4_0]
  implicit val decoder: Decoder[DatasetMetadataV4_0] =
    deriveDecoder[DatasetMetadataV4_0]
}

object DatasetMetadataV3_0 {
  implicit val encoder: Encoder[DatasetMetadataV3_0] =
    deriveEncoder[DatasetMetadataV3_0]
  implicit val decoder: Decoder[DatasetMetadataV3_0] =
    deriveDecoder[DatasetMetadataV3_0]
}

object DatasetMetadataV2_0 {
  implicit val encoder: Encoder[DatasetMetadataV2_0] =
    deriveEncoder[DatasetMetadataV2_0]
  implicit val decoder: Decoder[DatasetMetadataV2_0] =
    deriveDecoder[DatasetMetadataV2_0]
}

object DatasetMetadataV1_0 {
  implicit val encoder: Encoder[DatasetMetadataV1_0] =
    deriveEncoder[DatasetMetadataV1_0]
  implicit val decoder: Decoder[DatasetMetadataV1_0] =
    deriveDecoder[DatasetMetadataV1_0]
}

object DatasetMetadata {

  implicit val decodeEvent: Decoder[DatasetMetadata] =
    new Decoder[DatasetMetadata] {
      final def apply(c: HCursor): Decoder.Result[DatasetMetadata] =
        for {

          //because "pennsieveSchemaVersion" key is either an Int or a String, we need to associate a default value when
          //we try to decode it to the other type otherwise it errors and does not decode the Metadata
          //hence the Left(_) => Right(...)
          intSchema <- c.get[Option[Int]]("pennsieveSchemaVersion") match {
            case Right(Some(a)) => Right(a)
            case Right(None) => Right(0)
            case Left(_) => Right(0)
          }

          stringSchema <- c.get[Option[String]]("pennsieveSchemaVersion") match {
            case Right(Some(a)) => Right(a)
            case Right(None) => Right("NotAString")
            case Left(_) => Right("NotAString")
          }

          datasetMetadata <- intSchema match {
            case 1 => c.as[DatasetMetadataV1_0]
            case 2 => c.as[DatasetMetadataV2_0]
            case _ => {
              stringSchema match {
                case "3.0" => c.as[DatasetMetadataV3_0]
                case "4.0" => c.as[DatasetMetadataV4_0]
                case _ =>
                  Left(
                    DecodingFailure(s"Could not recognize schema", c.history)
                  )
              }
            }
          }
        } yield datasetMetadata
    }
}

case class FileManifest(
  name: String,
  path: String,
  size: Long,
  fileType: FileType,
  sourcePackageId: Option[String] = None,
  id: Option[UUID] = None
) extends Ordered[FileManifest] {

  def this(
    path: String,
    size: Long,
    fileType: FileType,
    sourcePackageId: Option[String]
  ) =
    this(FilenameUtils.getName(path), path, size, fileType, sourcePackageId)

  // Order files lexicographically by path
  def compare(that: FileManifest) =
    new NameFileComparator()
      .compare(new java.io.File(this.path), new java.io.File(that.path))
}

object FileManifest {
  implicit val encoder: Encoder[FileManifest] =
    deriveEncoder[FileManifest].mapJson(_.mapObject(_.remove("id")))
  implicit val decoder: Decoder[FileManifest] = new Decoder[FileManifest] {
    final def apply(c: HCursor): Decoder.Result[FileManifest] =
      for {
        path <- c.downField("path").as[String]
        name <- c.downField("name").as[Option[String]]
        size <- c.downField("size").as[Long]
        fileType <- c.downField("fileType").as[FileType]
        sourcePackageId <- c.downField("sourcePackageId").as[Option[String]]
        id <- c.downField("id").as[Option[UUID]]

        mappedName = if (name.isEmpty) {
          FilenameUtils.getName(path)
        } else {
          name.get
        }
      } yield {
        new FileManifest(mappedName, path, size, fileType, sourcePackageId, id)
      }
  }

  def apply(
    /**
      * Used in older versions of the schema.
      */
    path: String,
    size: Long,
    fileType: FileType,
    sourcePackageId: Option[String]
  ): FileManifest = {
    FileManifest(
      FilenameUtils.getName(path),
      path,
      size,
      fileType,
      sourcePackageId
    )
  }
}
