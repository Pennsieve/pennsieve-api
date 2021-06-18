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
