package com.blackfynn.dtos

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import java.time.ZonedDateTime

import com.blackfynn.models.{ DatasetStatusLog, User }

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
