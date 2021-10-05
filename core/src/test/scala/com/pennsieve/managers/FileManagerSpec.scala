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

import com.pennsieve.domain.{ LockedDatasetError, PredicateError, ServiceError }
import com.pennsieve.models.FileType.GenericData
import com.pennsieve.models.PackageType.CSV
import com.pennsieve.models._
import com.pennsieve.models.FileObjectType.Source
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class FileManagerSpec extends BaseManagerSpec {

  "a view" should "be returned if it exists in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val file = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )
    val view = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getViews(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.View)
  }

  "a file" should "be returned in place of a view if no view exists in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val file = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getViews(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.File)
  }

  "a source" should "be returned in place of a view if no view exists in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getViews(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.Source)
  }

  "a file" should "be returned if it exists in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val file = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getFiles(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.File)
  }

  "a source" should "be returned in place of a file if no file exists in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getFiles(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.Source)
  }

  "a source" should "always be returned regardless of other file types that exist in a package" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val file = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )
    val view = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getSources(pkg, None, None).await

    assert(fetched.isRight)
    assert(fetched.right.value.head.objectType === FileObjectType.Source)
  }

  "a file created by a user" should "be readable by that user" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)
    val file = createFile(container = pkg, user = user)
    val fm = fileManager(organization = testOrganization, user = user)

    assert(fm.get(file.id, pkg).await.isRight)
  }

  "files deleted from a package" should "not be accessible" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)
    val fileOne = createFile(
      name = "file-1",
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val fileTwo = createFile(
      name = "file-2",
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )
    val fileThree = createFile(
      name = "file-3",
      container = pkg,
      user = user,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )

    val fm = fileManager(organization = testOrganization, user = user)

    assert(fm.get(fileOne.id, pkg).await.isRight)
    assert(fm.get(fileTwo.id, pkg).await.isRight)
    assert(fm.get(fileThree.id, pkg).await.isRight)

    var res = fm.delete(pkg).await
    assert(res.isRight && res.right.value == 3)
    assert(fm.get(fileOne.id, pkg).await.isLeft)
    assert(fm.get(fileTwo.id, pkg).await.isLeft)
    assert(fm.get(fileThree.id, pkg).await.isLeft)

    val fileFour = createFile(
      name = "file-4",
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed
    )
    val fileFive = createFile(
      name = "file-5",
      container = pkg,
      user = user,
      objectType = FileObjectType.File,
      processingState = FileProcessingState.NotProcessable
    )

    res = fm.delete(pkg, onlyIds = Some(Set(fileFour.id))).await
    assert(res.isRight && res.right.value === 1)
    assert(fm.get(fileFour.id, pkg).await.isLeft)
    assert(fm.get(fileFive.id, pkg).await.isRight)
  }

  "pending source files" should "be ignored if excludePending is true" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val source = createFile(
      container = pkg,
      user = user,
      objectType = FileObjectType.Source,
      processingState = FileProcessingState.Processed,
      uploadedState = Some(FileState.UPLOADED)
    )
    val file =
      createFile(container = pkg, user = user)
    val file2 =
      createFile(
        container = pkg,
        user = user,
        uploadedState = Some(FileState.PENDING)
      )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getSources(pkg, None, None, excludePending = true).await

    assert(
      fetched.right.get
        .map(f => (f.id, f.uploadedState))
        .toSet == Set((source.id, Some(FileState.UPLOADED)), (file.id, None))
    )
  }

  "pending source files" should "not be ignored if excludePending is false" in {

    val user = createUser()
    val pkg = createPackage(testOrganization, user)

    val file =
      createFile(
        container = pkg,
        user = user,
        uploadedState = Some(FileState.PENDING)
      )

    val fm = fileManager(organization = testOrganization, user = user)
    val fetched = fm.getSources(pkg, None, None, excludePending = false).await

    assert(
      fetched.right.value.map(_.uploadedState) == List(Some(FileState.PENDING))
    )
  }

//  "files" should "not be created if it does not follow naming conventions" in {
//
//    val user = createUser()
//    val pkg = createPackage(testOrganization, user)
//
//    val fm = fileManager(organization = testOrganization, user = user)
//
//    val fileCreationResult = fm
//      .create(
//        name = "Test++",
//        `type` = FileType.GenericData,
//        `package` = pkg,
//        s3Bucket = "bucket/" + generateRandomString(),
//        s3Key = "key/" + generateRandomString(),
//        objectType = Source,
//        processingState = FileProcessingState.Unprocessed,
//        size = 0,
//        uploadedState = None
//      )
//      .await
//
//    assert(
//      fileCreationResult === Left(
//        PredicateError(
//          s"Invalid file name, please follow the naming conventions"
//        )
//      )
//    )
//  }

}
