// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum._

sealed trait Imaging
sealed trait PackageType extends EnumEntry

object PackageType extends Enum[PackageType] with CirceEnum[PackageType] {
  val values = findValues

  case object Image extends PackageType with Imaging
  case object MRI extends PackageType with Imaging
  case object Slide extends PackageType with Imaging

  // For tracking metadata associated external files that we don't manage or process the contents of:
  case object ExternalFile extends PackageType

  case object MSWord extends PackageType
  case object PDF extends PackageType
  // Old package type used for failed tabular imports
  case object CSV extends PackageType
  case object Tabular extends PackageType
  case object TimeSeries extends PackageType
  case object Video extends PackageType
  case object Unknown extends PackageType
  case object Collection extends PackageType
  case object Text extends PackageType
  case object Unsupported extends PackageType
  case object HDF5 extends PackageType
  case object ZIP extends PackageType

  // TODO: DELETE
  case object DataSet extends PackageType
}
