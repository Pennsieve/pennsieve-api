package com.pennsieve.clients

import enumeratum._

sealed trait RequestType extends EnumEntry

object RequestType extends Enum[RequestType] with CirceEnum[RequestType] {
  val values = findValues

  case object GET extends RequestType
  case object SET extends RequestType
}
