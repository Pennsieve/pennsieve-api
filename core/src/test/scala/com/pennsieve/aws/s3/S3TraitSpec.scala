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

package com.pennsieve.aws.s3

import com.amazonaws.services.s3.model._
import com.pennsieve.test._
import com.pennsieve.test.helpers._
import com.pennsieve.utilities.Container
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.apache.commons.io.IOUtils
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.util.Random

class S3TraitSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with PersistantTestContainers
    with S3DockerContainer {
  self: TestSuite =>

  def s3: S3 = {
    new S3(s3Container.s3Client)
  }

  lazy val littleContent = "little words"
  lazy val bigContent = Random.alphanumeric.take(21 * 1024 * 1024).mkString
  lazy val fiveMegabytes = 5 * 1024 * 1024 // S3 chunk size minimum

  "objectSummaries" should "list all objects under a prefix bucket" in {

    val bucket = s3.createBucket("list-object-bucket").right.get.getName

    // list objects only returns 1000 objects. Create more than that to test pagination:

    for {
      i <- (1 to 2500).toList
    } yield s3.putObject(bucket, s"prefix/$i.txt", "data").right.get

    s3.objectSummaries(bucket, "prefix").right.get.length shouldBe 2500
  }

  def createSourceAndDestBuckets(): (String, String) = {
    val uuid = UUID.randomUUID()

    val sourceBucket =
      s3.createBucket(s"source-bucket-$uuid").right.get

    val destBucket =
      s3.createBucket(s"dest-bucket-$uuid").right.get

    (sourceBucket.getName, destBucket.getName)
  }

  "multipartCopy" should "copy a small object" in {
    val (sourceBucket, destBucket) = createSourceAndDestBuckets()

    s3.putObject(sourceBucket, "source/small-file.txt", "little words")

    s3.multipartCopy(
        sourceBucket,
        "source/small-file.txt",
        destBucket,
        "dest/small-file.txt"
      )
      .right
      .get

    s3.getObject(destBucket, "dest/small-file.txt")
      .map(_.getObjectContent)
      .map(scala.io.Source.fromInputStream(_).mkString) shouldBe Right(
      "little words"
    )
  }

  "multipartCopy" should "copy metadata on a small object" in {
    val (sourceBucket, destBucket) = createSourceAndDestBuckets()

    val metadata = new ObjectMetadata()
    metadata.addUserMetadata("chunk-size", "41943040")
    metadata.setContentLength(littleContent.getBytes("UTF-8").length)

    s3.putObject(
      sourceBucket,
      "source/small-file.txt",
      IOUtils.toInputStream(littleContent, "UTF-8"),
      metadata
    )

    s3.multipartCopy(
        sourceBucket,
        "source/small-file.txt",
        destBucket,
        "dest/small-file.txt"
      )
      .right
      .get

    s3.getObject(destBucket, "dest/small-file.txt")
      .map(_.getObjectMetadata().getUserMetaDataOf("chunk-size")) shouldBe Right(
      "41943040"
    )

  }

  "multipartCopy" should "copy a big object" in {
    val (sourceBucket, destBucket) = createSourceAndDestBuckets()

    s3.putObject(sourceBucket, "source/big-file.txt", bigContent)

    s3.multipartCopy(
        sourceBucket,
        "source/big-file.txt",
        destBucket,
        "dest/big-file.txt",
        multipartChunkSize = fiveMegabytes,
        multipartCopyLimit = fiveMegabytes
      )
      .right
      .get
      .right
      .get shouldBe an[CompleteMultipartUploadResult]

    s3.getObject(destBucket, "dest/big-file.txt")
      .map(_.getObjectContent)
      .map(scala.io.Source.fromInputStream(_).mkString) shouldBe Right(
      bigContent
    )
  }

  "multipartCopy" should "copy metadata on a big object" in {
    val (sourceBucket, destBucket) = createSourceAndDestBuckets()

    val metadata = new ObjectMetadata()
    metadata.addUserMetadata("chunk-size", "41943040")
    metadata.setContentLength(bigContent.getBytes("UTF-8").length)

    s3.putObject(
      sourceBucket,
      "source/big-file.txt",
      IOUtils.toInputStream(bigContent, "UTF-8"),
      metadata
    )

    val result = s3
      .multipartCopy(
        sourceBucket,
        "source/big-file.txt",
        destBucket,
        "dest/big-file.txt",
        multipartChunkSize = fiveMegabytes,
        multipartCopyLimit = fiveMegabytes
      )
      .right
      .get
      .right
      .get shouldBe an[CompleteMultipartUploadResult]

    s3.getObject(destBucket, "dest/big-file.txt")
      .map(_.getObjectMetadata().getUserMetaDataOf("chunk-size")) shouldBe Right(
      "41943040"
    )
  }

  "multipartCopy" should "copy a big object when size is close to chunk size" in {
    val (sourceBucket, destBucket) = createSourceAndDestBuckets()

    val tenMegabytes = 2 * fiveMegabytes

    // Check boundary conditions

    for (s <- ((tenMegabytes - 5) to (tenMegabytes + 5))) {
      val fileName = s"big-file-$s.txt"
      val content = bigContent.slice(0, s)

      s3.putObject(sourceBucket, fileName, content)

      val result = s3
        .multipartCopy(
          sourceBucket,
          fileName,
          destBucket,
          fileName,
          multipartChunkSize = fiveMegabytes,
          multipartCopyLimit = fiveMegabytes
        )
        .right
        .get
        .right
        .get shouldBe an[CompleteMultipartUploadResult]

      s3.getObject(destBucket, fileName)
        .map(_.getObjectContent)
        .map(scala.io.Source.fromInputStream(_).mkString) shouldBe Right(
        content
      )
    }
  }
}
