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

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import java.time.ZonedDateTime

import com.pennsieve.models.{ DatasetStatusLog, User }

case class StatusLogEntryDTO(
  user: Option[UserStubDTO],
  status: DatasetStatusStubDTO,
  updatedAt: ZonedDateTime
)

object StatusLogEntryDTO {
  implicit val encoder: Encoder[StatusLogEntryDTO] =
    deriveEncoder[StatusLogEntryDTO]
  implicit val decoder: Decoder[StatusLogEntryDTO] =
    deriveDecoder[StatusLogEntryDTO]

  def apply(
    datasetStatusLog: DatasetStatusLog,
    user: Option[User]
  ): StatusLogEntryDTO =
    StatusLogEntryDTO(
      user = user.map(
        user => UserStubDTO(user.nodeId, user.firstName, user.lastName)
      ),
      status = DatasetStatusStubDTO(
        datasetStatusLog.statusId,
        datasetStatusLog.statusName,
        datasetStatusLog.statusDisplayName
      ),
      updatedAt = datasetStatusLog.createdAt
    )
}

case class UserStubDTO(nodeId: String, firstName: String, lastName: String)

object UserStubDTO {
  implicit val encoder: Encoder[UserStubDTO] =
    deriveEncoder[UserStubDTO]
  implicit val decoder: Decoder[UserStubDTO] =
    deriveDecoder[UserStubDTO]
}

case class DatasetStatusStubDTO(
  id: Option[Int],
  name: String,
  displayName: String
)

object DatasetStatusStubDTO {
  implicit val encoder: Encoder[DatasetStatusStubDTO] =
    deriveEncoder[DatasetStatusStubDTO]
  implicit val decoder: Decoder[DatasetStatusStubDTO] =
    deriveDecoder[DatasetStatusStubDTO]
}
