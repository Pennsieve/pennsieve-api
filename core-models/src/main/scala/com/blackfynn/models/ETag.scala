package com.blackfynn.models

import java.time.ZonedDateTime

/**
  * Generic ETag attribute. Pennsieve ETags are generated from millisecond
  * precision timestamps.
  *
  * See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag
  */
case class ETag(datetime: ZonedDateTime) extends AnyVal {

  def asHeader: String = datetime.toInstant.toEpochMilli.toString

}

object ETag {
  def touch: ETag = ETag(ZonedDateTime.now())
}
