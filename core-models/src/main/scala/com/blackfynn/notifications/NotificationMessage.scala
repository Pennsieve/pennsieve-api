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

package com.pennsieve.notifications

import java.time.OffsetDateTime

import com.pennsieve.dtos.PackageDTO
import com.pennsieve.models.{
  FileType,
  PackageType,
  PayloadType,
  PublishStatus
}
import com.pennsieve.notifications.MessageType._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.java8.time._

sealed trait NotificationMessage {
  val users: List[Int]
  val messageType: MessageType
  val message: String
}

object NotificationMessage {
  implicit val encoder: Encoder[NotificationMessage] =
    deriveEncoder[NotificationMessage]
  implicit val decoder: Decoder[NotificationMessage] =
    deriveDecoder[NotificationMessage]
}

final case class KeepAlive(
  users: List[Int],
  messageType: MessageType = KeepAliveT,
  message: String
) extends NotificationMessage

object KeepAlive {
  implicit val encoder: Encoder[KeepAlive] = deriveEncoder[KeepAlive]
  implicit val decoder: Decoder[KeepAlive] = deriveDecoder[KeepAlive]
}

final case class Ping(
  users: List[Int],
  messageType: MessageType = PingT,
  message: String,
  timestamp: Long
) extends NotificationMessage

object Ping {
  implicit val encoder: Encoder[Ping] = deriveEncoder[Ping]
  implicit val decoder: Decoder[Ping] = deriveDecoder[Ping]
}

final case class Pong(
  users: List[Int],
  messageType: MessageType = PongT,
  message: String,
  sessionId: String
) extends NotificationMessage

object Pong {
  implicit val encoder: Encoder[Pong] = deriveEncoder[Pong]
  implicit val decoder: Decoder[Pong] = deriveDecoder[Pong]
}

final case class AlertNotification(
  users: List[Int],
  messageType: MessageType = Alert,
  message: String
) extends NotificationMessage

object AlertNotification {
  implicit val encoder: Encoder[AlertNotification] =
    deriveEncoder[AlertNotification]
  implicit val decoder: Decoder[AlertNotification] =
    deriveDecoder[AlertNotification]
}

final case class DatasetUpdateNotification(
  users: List[Int],
  messageType: MessageType = DatasetUpdate,
  datasetId: Int,
  datasetName: String,
  message: String
) extends NotificationMessage

object DatasetUpdateNotification {
  implicit val encoder: Encoder[DatasetUpdateNotification] =
    deriveEncoder[DatasetUpdateNotification]
  implicit val decoder: Decoder[DatasetUpdateNotification] =
    deriveDecoder[DatasetUpdateNotification]
}

final case class JobDoneNotification(
  users: List[Int],
  messageType: MessageType = JobDone,
  datasetName: String,
  packageDTO: PackageDTO,
  message: String
) extends NotificationMessage

object JobDoneNotification {
  implicit val encoder: Encoder[JobDoneNotification] =
    deriveEncoder[JobDoneNotification]
  implicit val decoder: Decoder[JobDoneNotification] =
    deriveDecoder[JobDoneNotification]
}

final case class MentionNotification(
  users: List[Int],
  messageType: MessageType = Mention,
  message: String,
  packageId: String,
  packageName: String
) extends NotificationMessage

object MentionNotification {
  implicit val encoder: Encoder[MentionNotification] =
    deriveEncoder[MentionNotification]
  implicit val decoder: Decoder[MentionNotification] =
    deriveDecoder[MentionNotification]
}

final case class InternalNotification(
  users: List[Int],
  messageType: MessageType = Internal,
  message: String
) extends NotificationMessage

object InternalNotification {
  implicit val encoder: Encoder[InternalNotification] =
    deriveEncoder[InternalNotification]
  implicit val decoder: Decoder[InternalNotification] =
    deriveDecoder[InternalNotification]
}

final case class ETLNotification(
  users: List[Int],
  messageType: MessageType,
  success: Boolean,
  jobType: PayloadType,
  importId: String,
  organizationId: Int,
  packageId: Int,
  datasetId: Int,
  uploadedFiles: List[String],
  fileType: FileType,
  packageType: PackageType,
  message: String
) extends NotificationMessage

object ETLNotification {
  implicit val encoder: Encoder[ETLNotification] =
    deriveEncoder[ETLNotification]
  implicit val decoder: Decoder[ETLNotification] =
    deriveDecoder[ETLNotification]
}

final case class ETLExportNotification(
  users: List[Int],
  messageType: MessageType,
  success: Boolean,
  jobType: PayloadType,
  importId: String,
  organizationId: Int,
  packageId: Int,
  datasetId: Int,
  fileType: FileType,
  packageType: PackageType,
  sourcePackageId: Int,
  sourcePackageType: PackageType,
  message: String
) extends NotificationMessage

object ETLExportNotification {
  implicit val encoder: Encoder[ETLExportNotification] =
    deriveEncoder[ETLExportNotification]
  implicit val decoder: Decoder[ETLExportNotification] =
    deriveDecoder[ETLExportNotification]
}

final case class UploadNotification(
  users: List[Int],
  success: Boolean,
  datasetId: Int,
  packageId: Int,
  organizationId: Int,
  uploadedFiles: List[String]
) extends NotificationMessage {
  override val messageType: MessageType = JobDone
  override val message: String = "Upload job complete"
}

object UploadNotification {
  implicit val encoder: Encoder[UploadNotification] =
    deriveEncoder[UploadNotification]
  implicit val decoder: Decoder[UploadNotification] =
    deriveDecoder[UploadNotification]
}

final case class DatasetPublishNotification(
  users: List[Int],
  messageType: MessageType,
  message: String,
  datasetId: String,
  success: Boolean,
  failure: Option[String]
) extends NotificationMessage

object DatasetPublishNotification {
  implicit val encoder: Encoder[DatasetPublishNotification] =
    deriveEncoder[DatasetPublishNotification]
  implicit val decoder: Decoder[DatasetPublishNotification] =
    deriveDecoder[DatasetPublishNotification]
}

final case class DiscoverPublishNotification(
  users: List[Int],
  messageType: MessageType,
  message: String,
  sourceDatasetId: Int,
  publishedDatasetId: Option[Int],
  publishedVersionCount: Int,
  lastPublishedDate: Option[OffsetDateTime],
  status: PublishStatus,
  success: Boolean,
  failure: Option[String]
) extends NotificationMessage

object DiscoverPublishNotification {
  implicit val encoder: Encoder[DiscoverPublishNotification] =
    deriveEncoder[DiscoverPublishNotification]
  implicit val decoder: Decoder[DiscoverPublishNotification] =
    deriveDecoder[DiscoverPublishNotification]
}

final case class DatasetImportNotification(
  users: List[Int],
  messageType: MessageType,
  message: String,
  datasetId: Option[String],
  success: Boolean,
  failure: Option[String]
) extends NotificationMessage

object DatasetImportNotification {
  implicit val encoder: Encoder[DatasetImportNotification] =
    deriveEncoder[DatasetImportNotification]
  implicit val decoder: Decoder[DatasetImportNotification] =
    deriveDecoder[DatasetImportNotification]
}
