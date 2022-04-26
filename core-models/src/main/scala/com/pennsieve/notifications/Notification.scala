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

import java.time.ZonedDateTime

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import scala.collection.immutable

case class Notification(
  messageType: MessageType,
  userId: Int,
  deliveryMethod: String,
  messageContent: NotificationMessage,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
)

object Notification {
  implicit val encoder: Encoder[Notification] = deriveEncoder[Notification]
  implicit val decoder: Decoder[Notification] = deriveDecoder[Notification]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}

sealed trait MessageType extends EnumEntry

object MessageType extends Enum[MessageType] with CirceEnum[MessageType] {

  val values: immutable.IndexedSeq[MessageType] = findValues

  case object Alert extends MessageType
  case object DatasetUpdate extends MessageType
  case object Internal extends MessageType
  case object JobDone extends MessageType
  case object KeepAliveT extends MessageType
  case object Mention extends MessageType
  case object PingT extends MessageType
  case object PongT extends MessageType
  case object Unknown extends MessageType
}
