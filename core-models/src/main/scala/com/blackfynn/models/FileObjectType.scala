// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum._
import enumeratum.EnumEntry._

sealed trait FileObjectType extends EnumEntry with Snakecase

object FileObjectType
    extends Enum[FileObjectType]
    with CirceEnum[FileObjectType] {
  val values = findValues

  case object View extends FileObjectType
  case object File extends FileObjectType
  case object Source extends FileObjectType

  override def withNameOption(name: String) =
    name match {
      case "Source" | "Sources" | "source" | "sources" => Some(Source)
      case "File" | "file" | "Files" | "files" => Some(File)
      case "View" | "view" | "Views" | "views" => Some(View)
      case _ => super.withNameOption(name)
    }

  override def withNameInsensitiveOption(name: String) =
    withNameOption(name.toLowerCase)

  override def withNameUppercaseOnlyOption(name: String) =
    withNameOption(name.toUpperCase)

  override def withNameLowercaseOnlyOption(name: String) =
    withNameOption(name.toLowerCase)

}
