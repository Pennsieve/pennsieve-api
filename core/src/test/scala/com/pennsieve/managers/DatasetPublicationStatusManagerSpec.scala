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

package com.pennsieve.managers

import com.pennsieve.domain.CoreError
import com.pennsieve.managers.DatasetManager.{ OrderByColumn, OrderByDirection }
import com.pennsieve.models._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.matchers.should.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class DatasetPublicationStatusManagerSpec extends BaseManagerSpec {
  "on creation, a dataset" should "have no Dataset Publication Status" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)

    val dpsm = datasetPublicationStatusManager(testOrganization)

    val dataset1 = dm.create(name).await.value

    val publicationStatus = dpsm.getLatestByDataset(dataset1.id).await.value

    assert(publicationStatus === None)
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

    val latestPublicationStatus =
      dpsm.getLatestByDataset(dataset1.id).await.value

    assert(latestPublicationStatus === Some(datasetPublicationStatus1))
  }

  "Creating a Removal status with removal metadata" should "round-trip through getLatestByDataset" in {

    val dm = datasetManager(testOrganization, superAdmin)
    val dpsm = datasetPublicationStatusManager(testOrganization)

    val dataset1 = dm.create("RemovalMetadataDataset").await.value

    val metadata = RemovalRestoreMetadata(
      executionArn =
        Some("arn:aws:states:us-east-1:000000000000:execution:mock:1"),
      publishedVersion = Some(3)
    )

    val created = dpsm
      .create(
        dataset1,
        PublicationStatus.Accepted,
        PublicationType.Removal,
        removalMetadata = Some(metadata)
      )
      .await
      .value

    created.removalMetadata shouldBe Some(metadata)

    val latest = dpsm.getLatestByDataset(dataset1.id).await.value
    latest.flatMap(_.removalMetadata) shouldBe Some(metadata)
  }

  "setRemovalMetadata" should "update removal metadata on the row in place, not create a new log entry" in {

    val dm = datasetManager(testOrganization, superAdmin)
    val dpsm = datasetPublicationStatusManager(testOrganization)

    val dataset1 = dm.create("SetRemovalMetadataDataset").await.value

    val created = dpsm
      .create(dataset1, PublicationStatus.Accepted, PublicationType.Removal)
      .await
      .value

    created.removalMetadata shouldBe None

    val metadata = RemovalRestoreMetadata(
      executionArn =
        Some("arn:aws:states:us-east-1:000000000000:execution:mock:2"),
      publishedVersion = Some(1)
    )

    dpsm.setRemovalMetadata(created.id, metadata).await.value

    val logRows = dpsm.getLogByDataset(dataset1.id).await.value
    logRows.map(_.removalMetadata) shouldBe Seq(Some(metadata))
  }

}
