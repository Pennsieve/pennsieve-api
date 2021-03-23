// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.notifications

import java.time.ZonedDateTime

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._
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
