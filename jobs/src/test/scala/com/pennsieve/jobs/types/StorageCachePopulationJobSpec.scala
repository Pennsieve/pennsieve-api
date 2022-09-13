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

package com.pennsieve.jobs.types

import akka.actor.ActorSystem
import akka.testkit.TestKitBase
import com.pennsieve.core.utilities.{ DatabaseContainer, InsecureContainer }
import com.pennsieve.domain.StorageAggregation
import com.pennsieve.domain.StorageAggregation.sorganizations
import com.pennsieve.managers.{ ManagerSpec, StorageManager }
import com.pennsieve.models.PackageState._
import com.pennsieve.models.PackageType.{ Collection, PDF, TimeSeries }
import com.pennsieve.models.{
  File,
  FileObjectType,
  FileProcessingState,
  Package
}
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class CachePopulationJobSpec
    extends AnyFlatSpec
    with SpecHelper
    with Matchers
    with TestKitBase
    with BeforeAndAfterAll
    with ManagerSpec {

  implicit lazy val system: ActorSystem = ActorSystem("CachePopulationJobSpec")
  implicit lazy val ec: ExecutionContext = system.dispatcher

  var pkg: Package = _
  var file: File = _

  var insecureContainer: DatabaseContainer = _

  override def afterStart(): Unit = {
    val config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)

    insecureContainer = new InsecureContainer(config) with DatabaseContainer {
      override val postgresUseSSL = false
    }

    super.afterStart()
  }

  override def afterAll(): Unit = {
    insecureContainer.db.close()
    super.afterAll()
  }

  "running the cache population job" should "populate the cache" in {

    pkg = createPackage(`type` = TimeSeries)
    file = createFile(container = pkg, size = 123)

    val job = new StorageCachePopulationJob(insecureContainer, false)

    assert(job.run().await.isRight)

    val storageManager =
      StorageManager.create(insecureContainer, testOrganization)
    val cached = storageManager
      .getStorage(StorageAggregation.spackages, List(pkg.id))
      .await
      .right
      .get

    val expected =
      Map(pkg.id -> Some(123L))
    assert(cached == expected)
  }

  "the cache population job" should "correctly evaluate package states" in {

    val readyPackage = createPackage(`type` = TimeSeries)
    val readyFile = createFile(container = readyPackage, size = 123)

    val errorPackage =
      createPackage(`type` = TimeSeries, state = PROCESSING_FAILED)
    val errorFile = createFile(container = errorPackage, size = 345)

    val unavailablePackage =
      createPackage(`type` = TimeSeries, state = UNAVAILABLE)
    val unavailableFile =
      createFile(container = unavailablePackage, size = 2000)

    val uploadedPackage = createPackage(`type` = TimeSeries, state = UPLOADED)
    val uploadedFile = createFile(container = uploadedPackage, size = 1000)

    val deletingPackage = createPackage(`type` = TimeSeries, state = DELETING)
    val deletingFile = createFile(container = deletingPackage, size = 3000)

    val processingPackage =
      createPackage(`type` = TimeSeries, state = PROCESSING)
    val processingFile = createFile(container = processingPackage, size = 5555)

    val viewPackage = createPackage(`type` = TimeSeries)
    val viewFile = createFile(
      container = viewPackage,
      size = 10000,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )

    val job = new StorageCachePopulationJob(insecureContainer, false)

    assert(job.run().await.isRight)

    val storageManager =
      StorageManager.create(insecureContainer, testOrganization)
    val cached = storageManager
      .getStorage(
        StorageAggregation.spackages,
        List(
          readyPackage.id,
          errorPackage.id,
          unavailablePackage.id,
          uploadedPackage.id,
          deletingPackage.id,
          processingPackage.id,
          viewPackage.id
        )
      )
      .await
      .right
      .get

    val expected =
      Map(
        readyPackage.id -> Some(123L),
        errorPackage.id -> Some(345L),
        unavailablePackage.id -> None,
        uploadedPackage.id -> Some(1000L),
        deletingPackage.id -> None,
        processingPackage.id -> Some(5555L),
        viewPackage.id -> None
      )
    assert(cached == expected)
  }

  "the cache population job" should "handle recursive package structures" in {

    val collection = createPackage(`type` = Collection)

    val emptyCollection = createPackage(`type` = Collection)

    val childPackage1 =
      createPackage(`type` = TimeSeries, parent = Some(collection))
    createFile(container = childPackage1, size = 10)
    val childPackage2 =
      createPackage(`type` = TimeSeries, parent = Some(collection))
    createFile(container = childPackage2, size = 200)
    createFile(container = childPackage2, size = 700)

    val childCollection =
      createPackage(`type` = Collection, parent = Some(collection))

    val grandChildPackage =
      createPackage(`type` = PDF, parent = Some(childCollection))
    createFile(container = grandChildPackage, size = 3000)

    val job = new StorageCachePopulationJob(insecureContainer, false)

    assert(job.run().await.isRight)

    val storageManager =
      StorageManager.create(insecureContainer, testOrganization)

    val cached = storageManager
      .getStorage(
        StorageAggregation.spackages,
        List(
          collection.id,
          childPackage1.id,
          childPackage2.id,
          childCollection.id,
          grandChildPackage.id,
          emptyCollection.id
        )
      )
      .await
      .right
      .get

    val expected =
      Map(
        collection.id -> Some(3910),
        childPackage1.id -> Some(10),
        childPackage2.id -> Some(900),
        childCollection.id -> Some(3000),
        grandChildPackage.id -> Some(3000),
        emptyCollection.id -> None
      )
    assert(cached == expected)

    assert(
      storageManager
        .getStorage(StorageAggregation.sdatasets, List(testDataset.id))
        .await
        .right
        .get == Map(testDataset.id -> Some(3910))
    )

    assert(
      storageManager
        .getStorage(
          StorageAggregation.sorganizations,
          List(testOrganization.id)
        )
        .await
        .right
        .get == Map(testOrganization.id -> Some(3910))
    )
  }

  "the cache population job" should "ignore descendents of deleting collections" in {

    val parentCollection = createPackage(`type` = Collection)

    // This collection has been deleted, but not yet removed
    val collection = createPackage(
      `type` = Collection,
      parent = Some(parentCollection),
      state = DELETING
    )

    // This children won't necessarilly be marked as DELETING, but should
    // not be counted in the storage.
    val childPackage1 =
      createPackage(`type` = TimeSeries, parent = Some(collection))
    createFile(container = childPackage1, size = 10)

    val childPackage2 =
      createPackage(`type` = TimeSeries, parent = Some(collection))
    createFile(container = childPackage2, size = 200)

    val job = new StorageCachePopulationJob(insecureContainer, false)

    assert(job.run().await.isRight)

    val storageManager =
      StorageManager.create(insecureContainer, testOrganization)

    val cached = storageManager
      .getStorage(
        StorageAggregation.spackages,
        List(parentCollection.id, collection.id)
      )
      .await
      .right
      .get

    val expected = Map(parentCollection.id -> None, collection.id -> None)
    assert(cached == expected)
  }

  "running the cache population job for a single org" should "populate the cache for that org" in {

    pkg = createPackage(`type` = TimeSeries)
    file = createFile(container = pkg, size = 123)

    val newOrganization = createOrganization()
    val newUser = createUser(
      isSuperAdmin = true,
      organization = Some(newOrganization),
      datasets = List.empty
    )
    val newDataset = createDataset(newOrganization, newUser, "newStorageTest")
    val newPackage1 = createPackage(
      newOrganization,
      newUser,
      newDataset,
      "new test package 1",
      READY,
      PDF,
      None
    )
    val newFile = createFile(
      container = newPackage1,
      organization = newOrganization,
      size = 10000
    )

    val storageManagerTestOrganization =
      StorageManager.create(insecureContainer, testOrganization)
    val storageManagerNewOrganization =
      StorageManager.create(insecureContainer, newOrganization)

    val testOrgStorageBefore = storageManagerTestOrganization
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .right
      .get
    assert(testOrgStorageBefore == Map(testOrganization.id -> None))
    val newOrgStorageBefore = storageManagerNewOrganization
      .getStorage(sorganizations, List(newOrganization.id))
      .await
      .right
      .get
    assert(newOrgStorageBefore == Map(newOrganization.id -> None))

    val job = new StorageCachePopulationJob(insecureContainer, false) // populate storage cache for all orgs
    assert(job.run().await.isRight)

    val testOrgStorageAfter = storageManagerTestOrganization
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .right
      .get
    assert(testOrgStorageAfter == Map(testOrganization.id -> Some(123L)))
    val newOrgStorageAfter = storageManagerNewOrganization
      .getStorage(sorganizations, List(newOrganization.id))
      .await
      .right
      .get
    assert(newOrgStorageAfter == Map(newOrganization.id -> Some(10000L)))

    // add another package to testOrganization
    val pkg2 = createPackage(`type` = TimeSeries)
    val file2 = createFile(container = pkg2, size = 1000)

    // add another package to newOrganization
    val newPackage2 = createPackage(
      newOrganization,
      newUser,
      newDataset,
      "new test package 2",
      READY,
      PDF,
      None
    )
    val newFile2 = createFile(
      container = newPackage2,
      organization = newOrganization,
      size = 9999
    )

    assert(job.run(Some(newOrganization.id)).await.isRight)

    val testOrgStorageAfter2 = storageManagerTestOrganization
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .right
      .get
    assert(testOrgStorageAfter2 == Map(testOrganization.id -> Some(123L))) // testOrg storage should stay the same
    val newOrgStorageAfter2 = storageManagerNewOrganization
      .getStorage(sorganizations, List(newOrganization.id))
      .await
      .right
      .get
    assert(newOrgStorageAfter2 == Map(newOrganization.id -> Some(19999L))) // newOrg storage should be updated
  }
}
