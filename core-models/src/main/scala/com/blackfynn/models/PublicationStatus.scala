// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Snakecase

import scala.collection.immutable

sealed trait PublicationStatus extends EnumEntry with Snakecase

object PublicationStatus
    extends Enum[PublicationStatus]
    with CirceEnum[PublicationStatus] {

  val values: immutable.IndexedSeq[PublicationStatus] = findValues

  val lockedStatuses: Seq[PublicationStatus] = Seq(Requested, Accepted, Failed)

  val systemStatuses: Seq[PublicationStatus] = Seq(Completed, Failed)

  val publisherStatuses: Seq[PublicationStatus] = Seq(Accepted, Rejected)

  val validTransitions: Map[Option[PublicationStatus], Seq[PublicationStatus]] =
    Map(
      None -> Seq(Requested),
      Some(Requested) -> Seq(Cancelled, Rejected, Accepted),
      Some(Cancelled) -> Seq(Requested),
      Some(Rejected) -> Seq(Requested, Cancelled),
      Some(Completed) -> Seq(Requested),
      Some(Failed) -> Seq(Rejected, Accepted),
      Some(Accepted) -> Seq(Failed, Completed),
      Some(Draft) -> Seq(Requested)
    )

  case object Draft extends PublicationStatus
  case object Requested extends PublicationStatus
  case object Cancelled extends PublicationStatus
  case object Rejected extends PublicationStatus
  case object Accepted extends PublicationStatus
  case object Failed extends PublicationStatus
  case object Completed extends PublicationStatus
}
