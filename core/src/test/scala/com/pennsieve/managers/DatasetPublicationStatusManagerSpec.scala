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
