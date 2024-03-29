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

import java.time.ZonedDateTime

final case class DataCanvas(
  id: Int = 0,
  name: String,
  description: String,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  nodeId: String,
  permissionBit: Int = 0,
  role: Option[String] = None,
  statusId: Int = 0,
  isPublic: Boolean = false
)

object DataCanvas {
  val tupled = (this.apply _).tupled
}

final case class DataCanvasEx(
  id: Int = 0,
  name: String,
  description: String,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  nodeId: String,
  permissionBit: Int = 0,
  role: Option[String] = None,
  statusId: Int = 0,
  isPublic: Boolean = false,
  organizationId: Int
)

object DataCanvasEx {
  val tupled = (this.apply _).tupled
}
