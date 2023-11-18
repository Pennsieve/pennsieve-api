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

package com.pennsieve.dtos

import com.pennsieve.models.{ NameValueProperty, ReferenceType, ReleaseOrigin }

import java.time.ZonedDateTime

trait DatasetDetailsDTO

case class DatasetReleaseDTO(
  id: Int,
  datasetId: Int,
  origin: ReleaseOrigin,
  url: String,
  label: String,
  marker: Option[String],
  releaseDate: Option[ZonedDateTime],
  properties: Option[List[NameValueProperty]],
  tags: Option[List[String]],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
) extends DatasetDetailsDTO

case class DatasetReferenceDTO(
  id: Int,
  datasetId: Int,
  referenceOrder: Int,
  referenceType: ReferenceType,
  referenceId: String,
  properties: Option[List[NameValueProperty]],
  tags: Option[List[String]],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
) extends DatasetDetailsDTO
