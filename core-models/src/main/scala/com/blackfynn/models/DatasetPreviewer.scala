// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime

case class DatasetPreviewer(
  datasetId: Int,
  userId: Int,
  embargoAccess: EmbargoAccess,
  dataUseAgreementId: Option[Int],
  createdAt: ZonedDateTime = ZonedDateTime.now,
  updatedAt: ZonedDateTime = ZonedDateTime.now
)
