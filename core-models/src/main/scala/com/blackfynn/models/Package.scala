// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.util.UUID
import java.time.ZonedDateTime

final case class Package(
  nodeId: String,
  name: String,
  `type`: PackageType,
  datasetId: Int,
  ownerId: Option[Int],
  state: PackageState = PackageState.READY,
  parentId: Option[Int] = None,
  importId: Option[UUID] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0,
  attributes: List[ModelProperty] = Nil
)
