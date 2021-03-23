// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import com.pennsieve.domain.CoreError
import com.pennsieve.managers.DatasetManager.{ OrderByColumn, OrderByDirection }
import com.pennsieve.models._
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.OptionValues._
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class DatasetPublicationStatusManagerSpec extends BaseManagerSpec {
  "on creation, a dataset" should "have no Dataset Publication Status" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)

    val dpsm = datasetPublicationStatusManager(testOrganization)

    val dataset1 = dm.create(name).await.value

    assert(dataset1.publicationStatusId === None)
  }

  "Adding a valid Dataset Publication Status" should "update the corresponding  Dataset Publication Status" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)

    val dpsm = datasetPublicationStatusManager(testOrganization)

    val dataset1 = dm.create(name).await.value

    val datasetPublicationStatus1 =
      dpsm
        .create(
          dataset1,
          PublicationStatus.Requested,
          PublicationType.Publication
        )
        .await
        .value

    val dataset2 = dm.get(dataset1.id).await.value

    assert(dataset2.publicationStatusId === Some(datasetPublicationStatus1.id))
  }

}
