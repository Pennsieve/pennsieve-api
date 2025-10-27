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

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.db.PackagesMapper
import com.pennsieve.domain.{ NameCheckError, NotFound, PredicateError }
import com.pennsieve.domain.StorageAggregation.{ sdatasets, spackages }
import com.pennsieve.models.{
  CollectionUpload,
  Package,
  PackageState,
  PackageType
}
import com.pennsieve.models.PackageType._
import com.pennsieve.traits.PostgresProfile.api._
import org.postgresql.util.PSQLException
import org.scalatest.matchers.should.Matchers._
import org.scalatest.EitherValues._
import org.scalatest.enablers.Messaging.messagingNatureOfThrowable

import java.util.UUID
import com.pennsieve.audit.middleware.TraceId

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class PackageManagerSpec extends BaseManagerSpec {

  "a package" should "be retrievable from the database" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val p = createPackage(user = user, dataset = dataset)

    assert(pm.get(p.id).await.isRight)
  }

  "a package's parent" should "be retrievable" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val child =
      createPackage(user = user, parent = Some(parent), dataset = dataset)

    assert(parent == pm.get(child.parentId.get).await.value)
  }

  "updating a package's name" should "succeed" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val p = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val updated = pm.update(p.copy(name = "Updated Name")).await.value

    assert(pm.get(p.id).await.value.name == updated.name)
  }

  "updating package with the same name" should "succeed" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val p = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    pm.update(p).await.value

    assert(pm.get(p.id).await.value.name == p.name)
  }

  "updating a package's name" should "fail with duplicate names between packages" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val name = "test"
    val p = createPackage(user = user, dataset = dataset)
    val pkg = createPackage(
      name = name,
      user = user,
      dataset = dataset,
      parent = Some(p),
      `type` = PackageType.Image
    )
    val pkg2 = createPackage(
      name = "myImage",
      user = user,
      dataset = dataset,
      parent = Some(p),
      `type` = PackageType.Image
    )
    val pm = packageManager(user = user)

    val result = pm.update(pkg2.copy(name = name)).await

    assert(result.isLeft)
  }

//  "updating a package's name" should "fail if the name has a forbidden character" in {
//    val user = createUser()
//    val dataset = createDataset(user = user)
//
//    val p = createPackage(user = user, dataset = dataset)
//    val pkg = createPackage(
//      name = "test",
//      user = user,
//      dataset = dataset,
//      parent = Some(p),
//      `type` = PackageType.Image
//    )
//
//    val pm = packageManager(user = user)
//
//    val result = pm.update(pkg.copy(name = "test+")).await
//
//    assert(result.isLeft)
//  }

//  "updating a package's name" should "fail if the name has a % that is not part of an html encoding sequence" in {
//    val user = createUser()
//    val dataset = createDataset(user = user)
//
//    val p = createPackage(user = user, dataset = dataset)
//    val pkg = createPackage(
//      name = "test",
//      user = user,
//      dataset = dataset,
//      parent = Some(p),
//      `type` = PackageType.Image
//    )
//
//    val pm = packageManager(user = user)
//
//    val result = pm.update(pkg.copy(name = "myImage%")).await
//
//    assert(result.isLeft)
//
//    val result2 = pm.update(pkg.copy(name = "myImage%26")).await
//
//    assert(result2.isRight)
//  }

  "checkName" should "not return a duplicate name error on an unused name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val pm = packageManager(user = user)

    assert(
      pm.checkName("my package", None, dataset, PackageType.Slide).await.isRight
    )
  }

  "checkName" should "add index to duplicate package names without numbers at end" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val result = pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val baseNameOne = baseName + " (1)"

    result.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == baseNameOne)
        assert(
          message == "unique naming constraint or naming convention violation"
        )
      case _ => fail("Result was not a NameCheckError")
    }

    // createPackage does not use checkName, so we need to simulate duplicate package addition
    val pkg2 = createPackage(
      user = user,
      name = baseNameOne,
      parent = Some(parent),
      dataset = dataset
    )

    val result2 =
      pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val baseNameTwo = baseName + " (2)"

    result2.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == baseNameTwo)
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "add index to duplicate package names and handle extension properly" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package.txt"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val result = pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val baseNameOne = "my package (1).txt"

    result.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == baseNameOne)
        assert(
          message == "unique naming constraint or naming convention violation"
        )
      case _ => fail("Result was not a NameCheckError")
    }
  }

// TODO: Currently the provided name is not invalid
//  "checkName" should "escape an invalid package name" in {
//    val user = createUser()
//    val dataset = createDataset(user = user)
//    val parent = createPackage(user = user, dataset = dataset)
//    val pm = packageManager(user = user)
//
//    val pkg = createPackage(
//      user = user,
//      name = "my package",
//      parent = Some(parent),
//      dataset = dataset
//    )
//
//    val result =
//      pm.checkName("my %package+", Some(parent), dataset, pkg.`type`).await
//    result.left.value match {
//      case NameCheckError(recommendation, message) =>
//        assert(recommendation == "my %package+")
//        assert(
//          message == "unique naming constraint or naming convention violation"
//        )
//      case _ => fail("Result was not a NameCheckError")
//    }
//  }

  "checkName" should "add index to duplicate package names without numbers at end and also apply naming convention" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package+"
    val escapedBaseName = "my package%2B"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val result = pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val escapedBaseNameOne = "my package+ (1)"

    result.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == escapedBaseNameOne)
        assert(
          message == "unique naming constraint or naming convention violation"
        )
      case _ => fail("Result was not a NameCheckError")
    }

    // createPackage does not use checkName, so we need to simulate duplicate package addition
    val pkg2 = createPackage(
      user = user,
      name = escapedBaseNameOne,
      parent = Some(parent),
      dataset = dataset
    )

    val result2 =
      pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val escapedBaseNameTwo = "my package+ (2)"

    result2.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == escapedBaseNameTwo)
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "add index to duplicate package names with numbers at end" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package 999"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val result = pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val baseNameOne = baseName + " (1)"

    result.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == baseNameOne)
        assert(
          message == "unique naming constraint or naming convention violation"
        )
      case _ => fail("Result was not a NameCheckError")
    }

    // createPackage does not use checkName, so we need to simulate duplicate package addition
    val pkg2 = createPackage(
      user = user,
      name = baseNameOne,
      parent = Some(parent),
      dataset = dataset
    )

    val result2 =
      pm.checkName(baseName, Some(parent), dataset, pkg2.`type`).await
    val baseNameTwo = baseName + " (2)"

    result2.left.value match {
      case NameCheckError(recommendation, message) =>
        assert(recommendation == baseNameTwo)
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "append random UUID to package name after duplicate names are exhausted" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    // create additional duplicate packages
    val duplicateThreshold = 10
    var i = 0
    for (i <- 1 to duplicateThreshold) {
      // createPackage does not use checkName, so we need to simulate duplicate package addition
      createPackage(
        user = user,
        name = baseName + " (" + i + ")",
        parent = Some(parent),
        dataset = dataset
      )
    }

    val result =
      pm.checkName(
          baseName,
          Some(parent),
          dataset,
          PackageType.Collection,
          duplicateThreshold
        )
        .await
    val dupError = result.left.value

    result.left.value match {
      case NameCheckError(recommendation, _) =>
        assert(
          recommendation.length == (baseName.length + " ()".length + java.util.UUID
            .randomUUID()
            .toString
            .length)
        )
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "interpret name that could be an indexed name as a non-indexed name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val baseNameOne = baseName + " (1)"
    val pkg = createPackage(
      user = user,
      name = baseNameOne,
      parent = Some(parent),
      dataset = dataset
    )

    val result =
      pm.checkName(baseNameOne, Some(parent), dataset, pkg.`type`).await

    val baseNameOneOne = baseNameOne + " (1)"
    result.left.value match {
      case NameCheckError(recommendation, _) =>
        assert(recommendation == baseNameOneOne)
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "assign duplicate names the smallest possible index" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val baseNameOne = baseName + " (1)"
    createPackage(
      user = user,
      name = baseNameOne,
      parent = Some(parent),
      dataset = dataset
    )

    val baseNameOneHundred = baseName + " (100)"
    createPackage(
      user = user,
      name = baseNameOneHundred,
      parent = Some(parent),
      dataset = dataset
    )

    val result = pm.checkName(baseName, Some(parent), dataset, pkg.`type`).await
    val baseNameTwo = baseName + " (2)"

    result.left.value match {
      case NameCheckError(recommendation, _) =>
        assert(recommendation == baseNameTwo)
      case _ => fail("Result was not a NameCheckError")
    }
  }

  "checkName" should "treat duplicate names with different case as the same name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val uppercaseName = baseName.toUpperCase()
    val result =
      pm.checkName(uppercaseName, Some(parent), dataset, pkg.`type`).await

    assert(result.isLeft)
  }

  "checkName" should "treat duplicate names with trailing spaces as the same name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "my package"
    val pkg = createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val uppercaseName = baseName + " "
    val result =
      pm.checkName(uppercaseName, Some(parent), dataset, pkg.`type`).await

    assert(result.isLeft)
  }

  "checkName" should "not allow a collection and package to share the same name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "testName"
    createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset
    )

    val duplicatePackageName =
      pm.checkName(baseName, Some(parent), dataset, PackageType.Image).await

    assert(duplicatePackageName.isLeft)

    val duplicateCollectionName =
      pm.checkName(baseName, Some(parent), dataset, PackageType.Collection)
        .await

    assert(duplicateCollectionName.isLeft)
  }

  "assertNameIsUnique" should "not allow a collection and package to share the same name" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val parent = createPackage(user = user, dataset = dataset)
    val pm = packageManager(user = user)

    val baseName = "testName"
    createPackage(
      user = user,
      name = baseName,
      parent = Some(parent),
      dataset = dataset,
      `type` = PackageType.Collection
    )

    val duplicatePackageName =
      database
        .run(
          pm.packagesMapper
            .assertNameIsUnique(
              baseName,
              Some(parent),
              dataset,
              PackageType.Image
            )
        )

    the[PredicateError] thrownBy {
      duplicatePackageName.await
    } should have message "package name must be unique"

    val duplicateCollectionName =
      database
        .run(
          pm.packagesMapper
            .assertNameIsUnique(
              baseName,
              Some(parent),
              dataset,
              PackageType.Collection
            )
        )

    the[PredicateError] thrownBy {
      duplicateCollectionName.await
    } should have message "package name must be unique"
  }

  "descendants" should "find all and only the descendants of a package" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val parent = createPackage(user = user, dataset = dataset)
    val childOne =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildOne =
      createPackage(user = user, parent = Some(childOne), dataset = dataset)
    val grandChildTwo =
      createPackage(user = user, parent = Some(childOne), dataset = dataset)
    val childTwo =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildThree =
      createPackage(user = user, parent = Some(childTwo), dataset = dataset)
    val childThree =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildFour =
      createPackage(user = user, parent = Some(childThree), dataset = dataset)

    val parentTwo = createPackage(user = user, dataset = dataset)
    val childFour =
      createPackage(user = user, parent = Some(parentTwo), dataset = dataset)

    val pm = packageManager(user = user)
    val result = pm.descendants(parent).await

    result should contain theSameElementsAs Set(
      childOne.id,
      childTwo.id,
      childThree.id,
      grandChildOne.id,
      grandChildTwo.id,
      grandChildThree.id,
      grandChildFour.id
    )
    result should contain noneOf (parent.id, parentTwo.id, childFour.id)
  }

  "ancestors" should "find all and only the ancestors of a package" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val parent = createPackage(user = user, dataset = dataset)
    val childOne =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildOne =
      createPackage(user = user, parent = Some(childOne), dataset = dataset)
    val grandChildTwo =
      createPackage(user = user, parent = Some(childOne), dataset = dataset)
    val childTwo =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildThree =
      createPackage(user = user, parent = Some(childTwo), dataset = dataset)
    val childThree =
      createPackage(user = user, parent = Some(parent), dataset = dataset)
    val grandChildFour =
      createPackage(user = user, parent = Some(childThree), dataset = dataset)

    val parentTwo = createPackage(user = user, dataset = dataset)
    val childFour =
      createPackage(user = user, parent = Some(parentTwo), dataset = dataset)

    val pm = packageManager(user = user)
    val result = pm.ancestors(grandChildOne).await.value

    result should equal(List(parent, childOne))
    result should contain noneOf (childTwo, childThree, childFour, grandChildTwo, grandChildThree, grandChildFour, parentTwo)
  }

  "deleting a package" should "decrement storage" in {
    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)
    val p = createPackage(user = user, dataset = dataset, `type` = Slide)
    val file = createFile(container = p, user = user, size = 12345)
    val storageMnger = storageManager(testOrganization)
    storageMnger.incrementStorage(spackages, file.size, p.id).await
    val packageStorageBefore = storageMnger
      .getStorage(spackages, List(p.id))
      .await
      .value
      .get(p.id)
      .flatten
    val datasetStorageBefore = storageMnger
      .getStorage(sdatasets, List(dataset.id))
      .await
      .value
      .get(dataset.id)
      .flatten
    assert(packageStorageBefore == Some(file.size))
    assert(datasetStorageBefore == Some(file.size))

    pm.delete(TraceId("ds"), p)(storageMnger).await

    val packageStorageAfter = storageMnger
      .getStorage(spackages, List(p.id))
      .await
      .value
      .get(p.id)
      .flatten
    val datasetStorageAfter = storageMnger
      .getStorage(sdatasets, List(dataset.id))
      .await
      .value
      .get(dataset.id)
      .flatten
    assert(packageStorageAfter == Some(0))
    assert(datasetStorageAfter == Some(0))
  }

  "deleting a package" should "decrement storage from nested packages" in {
    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)
    val storageMnger = storageManager(testOrganization)

    /**
      * Package structure looks like:
      *
      * ├── parent (155B)
      * |   ├── childOne (155B)
      * |   |   ├── grandChildOne (55B)
      * |   |   └── grandChildTwo (100B)
      * └── sibling (1000B)
      */
    val parent = createPackage(user = user, dataset = dataset)

    val childOne =
      createPackage(user = user, parent = Some(parent), dataset = dataset)

    val grandChildOne =
      createPackage(
        user = user,
        parent = Some(childOne),
        dataset = dataset,
        `type` = Slide
      )
    val grandChildOneFile =
      createFile(container = grandChildOne, user = user, size = 55)

    val grandChildTwo =
      createPackage(
        user = user,
        parent = Some(childOne),
        dataset = dataset,
        `type` = Slide
      )
    val grandChildTwoFile =
      createFile(container = grandChildTwo, user = user, size = 100)

    val sibling = createPackage(user = user, dataset = dataset, `type` = Slide)
    val siblingFile = createFile(container = sibling, user = user, size = 1000)

    List(parent, childOne, grandChildOne, grandChildTwo, sibling)
      .traverse(storageMnger.setPackageStorage)
      .value
      .await

    val packageStorageBefore = storageMnger
      .getStorage(spackages, List(parent.id))
      .await
      .value
      .get(parent.id)
      .flatten
    val datasetStorageBefore = storageMnger
      .getStorage(sdatasets, List(dataset.id))
      .await
      .value
      .get(dataset.id)
      .flatten
    assert(packageStorageBefore == Some(155))
    assert(datasetStorageBefore == Some(1155))

    pm.delete(TraceId("ds"), childOne)(storageMnger).await

    val packageStorageAfter = storageMnger
      .getStorage(spackages, List(parent.id))
      .await
      .value
      .get(parent.id)
      .flatten
    val datasetStorageAfter = storageMnger
      .getStorage(sdatasets, List(dataset.id))
      .await
      .value
      .get(dataset.id)
      .flatten
    assert(packageStorageAfter == Some(0))
    assert(datasetStorageAfter == Some(1000))
  }

  "creating packages with duplicate node ids" should "fail" in {
    val user = createUser()
    val packagesMapper = new PackagesMapper(testOrganization)
    val dataset = createDataset(user = user)

    val nodeId = s"N:collection:${java.util.UUID.randomUUID().toString}"

    val package1 = Package(
      nodeId = nodeId,
      name = "package1",
      `type` = PackageType.Collection,
      datasetId = dataset.id,
      ownerId = Some(user.id)
    )

    database
      .run((packagesMapper returning packagesMapper.map(_.id)) += package1)
      .await

    val package2 = package1.copy(name = "package2")

    val result = Try(
      database
        .run((packagesMapper returning packagesMapper.map(_.id)) += package2)
        .await
    ).toEither

    assert(result.isLeft)
    assert(result.left.value.isInstanceOf[PSQLException])

  }

  "createSingleCollection" should "create a single collection (no destination)" in {

    val packagesMapper = new PackagesMapper(testOrganization)

    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)

    val dataCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"

    val dataCollection =
      CollectionUpload(dataCollectionId, "data", None, 0)

    val one = pm
      .createSingleCollection(dataCollection, dataset, Some(user.id))
      .value

    val two = pm
      .createSingleCollection(dataCollection, dataset, Some(user.id))
      .value

    one.zip(two).await

    val data = database
      .run(packagesMapper.filter(_.nodeId === dataCollectionId).result)
      .await

    assert(data.length == 1)

    assert(data.head.parentId.isEmpty)

  }

  "createSingleCollection" should "create a single collection (with destination)" in {

    val packagesMapper = new PackagesMapper(testOrganization)

    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)
    val destination = createPackage(user = user, dataset = dataset)

    val dataCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"

    val dataCollection =
      CollectionUpload(dataCollectionId, "data", Some(destination.nodeId), 0)

    val one = pm
      .createSingleCollection(dataCollection, dataset, Some(user.id))
      .value

    val two = pm
      .createSingleCollection(dataCollection, dataset, Some(user.id))
      .value

    one.zip(two).await

    val data = database
      .run(packagesMapper.filter(_.nodeId === dataCollectionId).result)
      .await

    assert(data.length == 1)

    assert(data.head.parentId.contains(destination.id))

  }

  "createCollections" should "create a single set of nested collections (no destination)" in {

    val packagesMapper = new PackagesMapper(testOrganization)

    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)

    val dataCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"
    val subjectsCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"
    val imagesCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"

    val dataCollection =
      CollectionUpload(dataCollectionId, "data", None, 0)
    val subjectsCollection =
      CollectionUpload(
        subjectsCollectionId,
        "subjects",
        Some(dataCollectionId),
        1
      )
    val imagesCollection =
      CollectionUpload(
        imagesCollectionId,
        "images",
        Some(subjectsCollectionId),
        2
      )

    val one = pm
      .createCollections(
        None,
        dataset,
        List(dataCollection, subjectsCollection, imagesCollection),
        Some(user.id)
      )
      .value

    val two = pm
      .createCollections(
        None,
        dataset,
        List(dataCollection, subjectsCollection, imagesCollection),
        Some(user.id)
      )
      .value

    one.zip(two).await

    val data = database
      .run(packagesMapper.filter(_.nodeId === dataCollectionId).result)
      .await

    val subjects = database
      .run(packagesMapper.filter(_.nodeId === subjectsCollectionId).result)
      .await

    val images = database
      .run(packagesMapper.filter(_.nodeId === imagesCollectionId).result)
      .await

    assert(data.length == 1)
    assert(subjects.length == 1)
    assert(images.length == 1)

    assert(data.head.parentId.isEmpty)
    assert(subjects.head.parentId.contains(data.head.id))
    assert(images.head.parentId.contains(subjects.head.id))

  }

  "createCollections" should "create a single set of nested collections (with destination)" in {

    val packagesMapper = new PackagesMapper(testOrganization)

    val user = createUser()
    val pm = packageManager(user = user)
    val dataset = createDataset(user = user)
    val destination = createPackage(user = user, dataset = dataset)

    val dataCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"
    val subjectsCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"
    val imagesCollectionId =
      s"N:collection:${java.util.UUID.randomUUID().toString}"

    val dataCollection =
      CollectionUpload(dataCollectionId, "data", Some(destination.nodeId), 0)
    val subjectsCollection =
      CollectionUpload(
        subjectsCollectionId,
        "subjects",
        Some(dataCollectionId),
        1
      )
    val imagesCollection =
      CollectionUpload(
        imagesCollectionId,
        "images",
        Some(subjectsCollectionId),
        2
      )

    val one = pm
      .createCollections(
        Some(destination),
        dataset,
        List(dataCollection, subjectsCollection, imagesCollection),
        Some(user.id)
      )
      .value

    val two = pm
      .createCollections(
        Some(destination),
        dataset,
        List(dataCollection, subjectsCollection, imagesCollection),
        Some(user.id)
      )
      .value

    one.zip(two).await

    val data = database
      .run(packagesMapper.filter(_.nodeId === dataCollectionId).result)
      .await

    val subjects = database
      .run(packagesMapper.filter(_.nodeId === subjectsCollectionId).result)
      .await

    val images = database
      .run(packagesMapper.filter(_.nodeId === imagesCollectionId).result)
      .await

    assert(data.length == 1)
    assert(subjects.length == 1)
    assert(images.length == 1)

    assert(data.head.parentId.contains(destination.id))
    assert(subjects.head.parentId.contains(data.head.id))
    assert(images.head.parentId.contains(subjects.head.id))
  }

  "getByImportId" should "return the proper package" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val importId = UUID.fromString("00000000-1111-2222-3333-000000000000")

    val pkg = createPackage(
      user = user,
      dataset = dataset,
      name = "testPackage",
      importId = Some(importId)
    )

    pm.getByImportId(dataset, importId).await.value.id shouldBe pkg.id
  }

  "getPackagesPageWithFiles" should "return source files per package" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val p = createPackage(user = user, dataset = dataset)
    val file = createFile(p, name = "a-sd_g.h")
    val otherFile = createFile(p)

    val result1 =
      pm.getPackagesPageWithFiles(dataset, None, 5, None, None).await

    result1.value.map(_._2) shouldBe Vector(Seq(file, otherFile))
  }

  behavior of "package export"

  it should "have empty path for top-level package" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val p = createPackage(user = user, dataset = dataset)
    val result = pm.exportAll(dataset).await.value
    result.length shouldEqual 1
    result.head._2.length shouldEqual 0
  }

  it should "provide the folder path for packages" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)

    // create the folder structure
    val dataFolderName = "data"
    val sourceFolderName = "source"
    val derivedFolderName = "derived"

    val dataFolderPackage =
      createPackage(user = user, dataset = dataset, name = dataFolderName)
    val sourceFolderPackage = createPackage(
      user = user,
      dataset = dataset,
      name = sourceFolderName,
      parent = Some(dataFolderPackage)
    )
    val derivedFolderPackage = createPackage(
      user = user,
      dataset = dataset,
      name = derivedFolderName,
      parent = Some(dataFolderPackage)
    )

    // attach files to the packages
    val manifestFileName = "manifest.json"
    val sourceFileName = "source.dat"
    val derivedFileName = "derived.csv"

    val manifestFilePackage = createPackage(
      user = user,
      dataset = dataset,
      name = manifestFileName,
      `type` = PackageType.Unsupported,
      parent = Some(dataFolderPackage)
    )
    val manifestFile =
      createFile(container = manifestFilePackage, name = manifestFileName)

    val sourceFilePackage = createPackage(
      user = user,
      dataset = dataset,
      name = sourceFileName,
      `type` = PackageType.Unknown,
      parent = Some(sourceFolderPackage)
    )
    val sourceFile =
      createFile(container = sourceFolderPackage, name = sourceFileName)

    val derivedFilePackage = createPackage(
      user = user,
      dataset = dataset,
      name = derivedFileName,
      `type` = PackageType.CSV,
      parent = Some(derivedFolderPackage)
    )
    val derivedFile =
      createFile(container = derivedFolderPackage, name = derivedFileName)

    val result = pm.exportAll(dataset).await.value

    result.length shouldEqual (6)
    result
      .filter((_._1.name.equals(manifestFileName)))
      .map(_._2) shouldBe Vector(Seq(dataFolderName))
    result.filter((_._1.name.equals(sourceFileName))).map(_._2) shouldBe Vector(
      Seq(dataFolderName, sourceFolderName)
    )
    result
      .filter((_._1.name.equals(derivedFileName)))
      .map(_._2) shouldBe Vector(Seq(dataFolderName, derivedFolderName))
  }

  it should "return a zero-length response for a dataset with no packages" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)
    val result = pm.exportAll(dataset).await.value
    result.length shouldBe 0
  }

  it should "exclude packages under a deleted collection" in {
    val user = createUser()
    val pm = packageManager(user = user)

    val dataset = createDataset(user = user)

    // create the folder structure
    val primaryFolderName = "primary"
    val primaryFolderPackage =
      createPackage(user = user, dataset = dataset, name = primaryFolderName)

    val subject1FolderName = "sub-1"
    val subject1FolderPackage = createPackage(
      user = user,
      dataset = dataset,
      name = subject1FolderName,
      parent = Some(primaryFolderPackage)
    )

    val subject2FolderName = "sub-2"
    val subject2FolderPackage = createPackage(
      user = user,
      dataset = dataset,
      name = subject2FolderName,
      parent = Some(primaryFolderPackage)
    )

    // add a file into each subject's folder
    val subject1FileName = "sub-1.dat"
    val subject1FilePackage = createPackage(
      user = user,
      dataset = dataset,
      name = subject1FileName,
      `type` = PackageType.Unsupported,
      parent = Some(subject1FolderPackage)
    )
    val _ =
      createFile(container = subject1FilePackage, name = subject1FileName)

    val subject2FileName = "sub-2.dat"
    val subject2FilePackage = createPackage(
      user = user,
      dataset = dataset,
      name = subject2FileName,
      `type` = PackageType.Unsupported,
      parent = Some(subject2FolderPackage)
    )
    val _ =
      createFile(container = subject2FilePackage, name = subject2FileName)

    // Export Packages
    val beforeDelete = pm.exportAll(dataset).await.value
    beforeDelete.length shouldEqual 5

    // Delete the 'sub-2' Package (folder)
    val _ = deletePackage(
      organization = testOrganization,
      user = user,
      pkg = subject2FolderPackage
    )

    // Export Packages again: it should exclude 'sub-2' and 'sub-2.dat' packages
    val afterDelete = pm.exportAll(dataset).await.value
    afterDelete.length shouldEqual 3

    val paths =
      afterDelete.flatMap { case (_, paths) => Seq(paths) }.flatten.toList
    val hasDeletedNameInPath = paths.exists(s => s.contains("__DELETED__"))
    hasDeletedNameInPath shouldBe false

    // check that the `sub-2` and `sub-2.dat` packages are not present in the export
    val afterDeletePackageNodeIds = afterDelete.flatMap {
      case (pkg, _) => pkg.nodeId
    }
    afterDeletePackageNodeIds.contains(subject2FolderPackage.nodeId) shouldBe false
    afterDeletePackageNodeIds.contains(subject2FilePackage.nodeId) shouldBe false
  }

}
