package com.blackfynn.models

import enumeratum._
import enumeratum.EnumEntry._
import scala.collection.immutable

/**
  * Data type used to filter changelog event types. Each changelog event type
  * belongs to one broad event category.
  */
sealed trait ChangelogEventCategory extends EnumEntry with UpperSnakecase {
  self =>

  /**
    * Get all events types which belong to this category.
    */
  def eventTypes: List[ChangelogEventName] =
    ChangelogEventName.values
      .filter((eventType: ChangelogEventName) => eventType.category == self)
      .toList
}

object ChangelogEventCategory
    extends Enum[ChangelogEventCategory]
    with CirceEnum[ChangelogEventCategory] {

  val values: immutable.IndexedSeq[ChangelogEventCategory] = findValues

  case object DATASET extends ChangelogEventCategory
  case object PERMISSIONS extends ChangelogEventCategory
  case object PACKAGES extends ChangelogEventCategory
  case object MODELS_AND_RECORDS extends ChangelogEventCategory
  case object PUBLISHING extends ChangelogEventCategory
}
