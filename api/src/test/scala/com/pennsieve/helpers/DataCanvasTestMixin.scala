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

package com.pennsieve.helpers

import com.pennsieve.api.ApiSuite
import com.pennsieve.helpers.APIContainers.SecureAPIContainer
import com.pennsieve.models.{ DataCanvas, Dataset, Organization, Package }

import scala.util.Random

trait DataCanvasTestMixin extends DataSetTestMixin {

  self: ApiSuite =>

  val bogusCanvasId = 314159

  def randomString(length: Int = 32): String =
    Random.alphanumeric.take(length).mkString

  def setupCanvas(numberOfPackages: Int = 1) = {
    val dataset = createDataSet(randomString())
    val canvas = createDataCanvas()
    val packages = List.range(1, numberOfPackages + 1).map { i =>
      val `package` = createPackage(dataset, randomString())
      attachPackage(canvas, dataset, `package`)
      `package`
    }
    (canvas, dataset, packages)
  }

  def createDataCanvas(
    name: String = randomString(32),
    description: String = randomString(64),
    isPublic: Boolean = false,
    container: SecureAPIContainer = secureContainer
  ): DataCanvas =
    container.dataCanvasManager
      .create(name, description, isPublic = Some(isPublic))
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }

  def attachPackage(
    canvas: DataCanvas,
    dataset: Dataset,
    `package`: Package,
    organization: Organization = loggedInOrganization,
    container: SecureAPIContainer = secureContainer
  ): Unit =
    container.dataCanvasManager
      .attachPackage(canvas.id, dataset.id, `package`.id, organization.id)
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }

  def getAll(container: SecureAPIContainer = secureContainer): Seq[DataCanvas] =
    container.dataCanvasManager
      .getAll()
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }
}
