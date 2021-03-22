// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.managers

import com.blackfynn.test.helpers.EitherValue._
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class DatasetCollectionManagerSpec extends BaseManagerSpec {

  "a collectionManager" should "create a collection if the name is unique" in {

    val name = "TestCollectionName"

    val cm = datasetCollectionManager(testOrganization)

    val c1 = cm.create(name).await.value
    assert(c1.name == name)

    val c2 = cm.create(name).await
    assert(c2.isLeft)
  }

  "a collectionManager" should "not create a collection if the name is empty" in {

    val name = ""

    val cm = datasetCollectionManager(testOrganization)

    val c1 = cm.create(name).await
    assert(c1.isLeft)
  }

  "a collectionManager" should "list all the collections of an organization" in {

    val name = "AnotherTestCollectionName"

    val cm = datasetCollectionManager(testOrganization)

    val collectionList = cm.getCollections().await.value
    assert(collectionList.size == 0)

    cm.create(name).await.value

    val collectionList2 = cm.getCollections().await.value
    assert(collectionList2.size == collectionList.size + 1)
    assert(collectionList2(collectionList.size).name == name)
  }

  "a collectionManager" should "get an existing collection by id" in {
    val cm = datasetCollectionManager(testOrganization)

    val name = "TestCollectionName"
    cm.create(name).await

    val collectionList = cm.getCollections().await.value

    val c1 = cm.get(collectionList(0).id).await.value
    assert(c1 == collectionList(0))

  }

  "a collectionManager" should "return an Option[Collection] when using the maybeGet function" in {
    val cm = datasetCollectionManager(testOrganization)

    val name = "TestCollectionName"
    cm.create(name).await

    val collectionList = cm.getCollections().await.value

    val c1 = cm.maybeGet(collectionList(0).id).await.value
    assert(c1 == Some(collectionList(0)))

    val c2 = cm.maybeGet(10000).await.value
    assert(c2 == None)
  }

  "a collectionManager" should "update an existing collection if the new name is unique" in {
    val cm = datasetCollectionManager(testOrganization)

    val name = "TestCollectionName"
    cm.create(name).await

    val name2 = "TestCollectionAnotherName"
    cm.create(name2).await

    val collectionList = cm.getCollections().await.value

    val newName = "newNameForCollection"
    val c1 = cm.update(newName, collectionList(0)).await.value
    assert(c1.name == newName)

    val c2 = cm.update(newName, collectionList(1)).await
    assert(c2.isLeft)
  }

  "a collectionManager" should "not update an existing collection if the new name is empty" in {
    val cm = datasetCollectionManager(testOrganization)

    val name = "TestCollectionName"
    cm.create(name).await

    val collectionList = cm.getCollections().await.value

    val newName = ""
    val c1 = cm.update(newName, collectionList(0)).await
    assert(c1.isLeft)
  }

  "a collectionManager" should "delete an existing collection" in {
    val cm = datasetCollectionManager(testOrganization)

    val name = "TestCollectionName"
    cm.create(name).await

    val name2 = "TestCollectionAnotherName"
    cm.create(name2).await

    val collectionList = cm.getCollections().await.value

    val c1 = collectionList(0)
    val c2 = collectionList(1)

    cm.delete(c2).await.value

    val collectionList2 = cm.getCollections().await.value
    assert(collectionList2.size == collectionList.size - 1)
    assert(collectionList2 == List(c1))
  }
}
