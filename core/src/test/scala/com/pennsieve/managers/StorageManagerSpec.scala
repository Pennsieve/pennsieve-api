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

import com.pennsieve.domain.StorageAggregation.{
  sdatasets,
  sorganizations,
  spackages,
  susers
}
import com.pennsieve.db._
import com.pennsieve.models.PackageType._
import com.pennsieve.models.{
  Dataset,
  File,
  FileObjectType,
  FileProcessingState,
  FileType,
  Package,
  PackageState
}
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.PackageType.PDF
import com.pennsieve.core.utilities._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities.Container
import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContextExecutor

class StorageManagerSpec extends BaseManagerSpec with Matchers {

  implicit lazy val system: ActorSystem = ActorSystem("StorageManagerSpec")
  implicit lazy val executionContext: ExecutionContextExecutor =
    system.dispatcher

  def createStuff(): (Package, Package, Package) = {
    val user = createUser()
    val dataset = createDataset(testOrganization, user, "storageTest")
    val package1 = createPackage(
      testOrganization,
      user,
      dataset,
      "test package 1",
      READY,
      PDF,
      None
    )
    val package2 = createPackage(
      testOrganization,
      user,
      dataset,
      "test package 2",
      READY,
      PDF,
      None
    )
    val package3 = createPackage(
      testOrganization,
      user,
      dataset,
      "test package 3",
      READY,
      PDF,
      Some(package2)
    )
    (package1, package2, package3)
  }

  def createNestedStuff(
  ): (Dataset, Package, Package, Package, Package, File) = {
    val user = createUser()
    val dataset = createDataset(testOrganization, user, "storageTest")
    val collection1 = createPackage(
      testOrganization,
      user,
      dataset,
      "test collection 1",
      READY,
      Collection,
      None
    )
    val collection2 = createPackage(
      testOrganization,
      user,
      dataset,
      "test collection 2",
      READY,
      Collection,
      Some(collection1)
    )
    val collection3 = createPackage(
      testOrganization,
      user,
      dataset,
      "test collection 3",
      READY,
      Collection,
      Some(collection2)
    )
    val pkg = createPackage(
      testOrganization,
      user,
      dataset,
      "test package 1",
      READY,
      PDF,
      Some(collection3)
    )
    val file = createFile(
      pkg,
      testOrganization,
      user,
      "test file",
      "bucket",
      "key",
      FileType.EDF,
      FileObjectType.Source,
      FileProcessingState.Processed,
      54321L
    )
    (dataset, collection1, collection2, collection3, pkg, file)
  }

  "storageManager" should "return empty for nonexisting cached storage numbers" in {
    val result = storageManager(testOrganization)
      .getStorage(sdatasets, List(123))
      .await
      .value
    assert(result == Map(123 -> None))
  }

  "storageManager" should "return storage sizes bigger than a 32bit int" in {
    val (pkg1, pkg2, pkg3) = createStuff()
    val big = Int.MaxValue.toLong * 2
    assert(big.toString == "4294967294")
    val storageMnger = storageManager(testOrganization)
    storageMnger.incrementStorage(spackages, big, pkg1.id).await.value
    val result =
      storageMnger.getStorage(spackages, List(pkg1.id)).await.value
    assert(result == Map(pkg1.id -> Some(big)))
  }

  "storageManager" should "return the correct number for cached storage sizes" in {
    val (pkg1, pkg2, pkg3) = createStuff()
    val storageMnger = storageManager(testOrganization)
    storageMnger.incrementStorage(spackages, 123456, pkg1.id).await.value
    val packageStorage =
      storageMnger.getStorage(spackages, List(pkg1.id)).await.value
    assert(packageStorage == Map(pkg1.id -> Some(123456)))
    val orgStorage = storageMnger
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .value
    assert(orgStorage == Map(testOrganization.id -> Some(123456)))
  }

  "multiple packages in a dataset" should "have their size added together" in {
    val (pkg1, pkg2, pkg3) = createStuff()
    val storageMnger = storageManager(testOrganization)
    storageMnger.incrementStorage(spackages, 123456, pkg1.id).await.value
    storageMnger.incrementStorage(spackages, 123456, pkg2.id).await.value
    val orgStorage = storageMnger
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .value
    assert(orgStorage == Map(testOrganization.id -> Some(123456 * 2)))
  }

  "updating with a negative number" should "decrease the usage" in {
    val (pkg1, pkg2, pkg3) = createStuff()
    val storageMnger = storageManager(testOrganization)
    storageMnger.incrementStorage(spackages, 123456, pkg1.id).await.value
    storageMnger.incrementStorage(spackages, -55, pkg2.id).await.value
    val orgStorage = storageMnger
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .value
    assert(orgStorage == Map(testOrganization.id -> Some(123401)))
  }

  "nestedCollections" should "reflect the size of their child packages" in {
    val (dataset, collection1, collection2, collection3, pkg, file) =
      createNestedStuff()
    val storageMnger = storageManager(testOrganization)

    storageMnger.incrementStorage(spackages, file.size, pkg.id).await.value

    val packageStorage =
      storageMnger.getStorage(spackages, List(pkg.id)).await.value
    assert(packageStorage == Map(pkg.id -> Some(54321)))

    val orgStorage = storageMnger
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .value
    assert(orgStorage == Map(testOrganization.id -> Some(54321)))

    val coll3Storage =
      storageMnger.getStorage(spackages, List(collection3.id)).await.value
    assert(coll3Storage == Map(collection3.id -> Some(54321)))

    val coll2Storage =
      storageMnger.getStorage(spackages, List(collection2.id)).await.value
    assert(coll2Storage == Map(collection2.id -> Some(54321)))

    val coll1Storage =
      storageMnger.getStorage(spackages, List(collection1.id)).await.value
    assert(coll1Storage == Map(collection1.id -> Some(54321)))

  }

  "circular collections" should "not crash incrementStorage" in {
    val (dataset, collection1, collection2, collection3, pkg, file) =
      createNestedStuff()
    val storageMnger = storageManager(testOrganization)

    // Create a circular dependency in the directory structure
    val packagesMapper = new PackagesMapper(testOrganization)
    database
      .run(
        packagesMapper
          .filter(_.id === collection1.id)
          .map(_.parentId)
          .update(Some(collection3.id))
      )
      .await

    storageMnger
      .incrementStorage(spackages, file.size, pkg.id)
      .awaitFinite()
      .value

    val orgStorage = storageMnger
      .getStorage(sorganizations, List(testOrganization.id))
      .await
      .value
    assert(orgStorage == Map(testOrganization.id -> Some(54321)))
  }

  "circular collections" should "not crash refreshPackageStorage" in {
    val (dataset, collection1, collection2, collection3, pkg, file) =
      createNestedStuff()
    val storageMnger = storageManager(testOrganization)

    // Create a circular dependency in the directory structure
    val packagesMapper = new PackagesMapper(testOrganization)

    val packageStorageMapper = new PackageStorageMapper(testOrganization)
    database
      .run(
        packagesMapper
          .filter(_.id === collection1.id)
          .map(_.parentId)
          .update(Some(collection3.id))
      )
      .await

    database
      .run(packageStorageMapper.refreshPackageStorage(dataset))
      .awaitFinite()
  }

  "setPackageStorage" should "only increment package storage once" in {
    val user = createUser()
    val dataset = createDataset(testOrganization, user, "storageTest")
    val pkg1 = createPackage(
      testOrganization,
      user,
      dataset,
      "test package 1",
      READY,
      PDF,
      None
    )
    val file1 = createFile(
      pkg1,
      testOrganization,
      user,
      "test file",
      "bucket",
      "key",
      FileType.EDF,
      FileObjectType.Source,
      FileProcessingState.Unprocessed,
      54321L
    )
    val file2 = createFile(
      pkg1,
      testOrganization,
      user,
      "test file",
      "bucket",
      "key",
      FileType.EDF,
      FileObjectType.View,
      FileProcessingState.NotProcessable,
      12345L
    )

    val storageManagerTestOrg = storageManager(testOrganization)
    val result = storageManagerTestOrg
      .setPackageStorage(pkg1)
      .await
      .value

    val storageAfter =
      storageManagerTestOrg.getStorage(spackages, List(pkg1.id)).await.value

    assert(storageAfter == Map(pkg1.id -> Some(54321L)))
  }
}
