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

package com.pennsieve.publish

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  ChecksumAlgorithm,
  CompleteMultipartUploadRequest,
  CompletedMultipartUpload,
  CompletedPart,
  CreateMultipartUploadRequest,
  GetObjectAttributesRequest,
  ObjectAttributes,
  RequestPayer,
  UploadPartCopyRequest
}

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

case class CompletedUpload(
  bucket: String,
  key: String,
  versionId: String,
  eTag: String,
  sha256: String
)

class MultipartUploader(s3Client: S3Client, maxPartSize: Long) {

  def copy(
    sourceBucket: String,
    sourceKey: String,
    destinationBucket: String,
    destinationKey: String
  )(implicit
    ec: ExecutionContext
  ): Future[CompletedUpload] = {

    def getObjectSize(sourceBucket: String, sourceKey: String): Long = {
      val getObjectAttributesRequest = GetObjectAttributesRequest
        .builder()
        .bucket(sourceBucket)
        .key(sourceKey)
        .requestPayer(RequestPayer.REQUESTER)
        .objectAttributes(List(ObjectAttributes.OBJECT_SIZE).asJava)
        .build()
      val getObjectAttributesResponse =
        s3Client.getObjectAttributes(getObjectAttributesRequest)
      getObjectAttributesResponse.objectSize()
    }

    def byteRange(offset: Long, size: Long): String =
      s"bytes=${offset}-${offset + size - 1}"

    @tailrec
    def parts(
      offset: Long,
      objectSize: Long,
      partSize: Long,
      accumulator: List[String]
    ): List[String] =
      if (objectSize <= partSize)
        byteRange(offset, objectSize) :: accumulator
      else
        parts(
          offset + partSize,
          objectSize - partSize,
          partSize,
          byteRange(offset, partSize) :: accumulator
        )

    def startUpload(
      destinationBucket: String,
      destinationKey: String
    ): String = {
      val createMultipartUploadRequest = CreateMultipartUploadRequest
        .builder()
        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
        .bucket(destinationBucket)
        .key(destinationKey)
        .requestPayer(RequestPayer.REQUESTER)
        .build()

      val createMultipartUploadResponse =
        s3Client.createMultipartUpload(createMultipartUploadRequest)
      createMultipartUploadResponse.uploadId()
    }

    def uploadPart(
      uploadId: String,
      index: Int,
      part: String
    ): (Int, CompletedPart) = {
      val uploadPartCopyRequest = UploadPartCopyRequest
        .builder()
        .uploadId(uploadId)
        .sourceBucket(sourceBucket)
        .sourceKey(sourceKey)
        .destinationBucket(destinationBucket)
        .destinationKey(destinationKey)
        .copySourceRange(part)
        .partNumber(index)
        .requestPayer(RequestPayer.REQUESTER)
        .build()

      val uploadPartCopyResponse =
        s3Client.uploadPartCopy(uploadPartCopyRequest)
      val copyPartResult = uploadPartCopyResponse.copyPartResult()

      val completedPart = CompletedPart
        .builder()
        .eTag(copyPartResult.eTag())
        .checksumSHA256(copyPartResult.checksumSHA256())
        .partNumber(index)
        .build()

      (index, completedPart)
    }

    def finishUpload(
      uploadId: String,
      completedParts: Seq[CompletedPart]
    ): CompletedUpload = {
      val completedMultipartUpload = CompletedMultipartUpload
        .builder()
        .parts(completedParts.asJava)
        .build()

      val completeMultipartUploadRequest = CompleteMultipartUploadRequest
        .builder()
        .bucket(destinationBucket)
        .key(destinationKey)
        .uploadId(uploadId)
        .multipartUpload(completedMultipartUpload)
        .requestPayer(RequestPayer.REQUESTER)
        .build()

      val completeMultipartUploadResponse =
        s3Client.completeMultipartUpload(completeMultipartUploadRequest)
      CompletedUpload(
        bucket = completeMultipartUploadResponse.bucket(),
        key = completeMultipartUploadResponse.key(),
        versionId = completeMultipartUploadResponse.versionId(),
        eTag = completeMultipartUploadResponse.eTag(),
        sha256 = completeMultipartUploadResponse.checksumSHA256()
      )
    }

    Future {
      // TODO: get max `partSize` from config
      val objectSize = getObjectSize(sourceBucket, sourceKey)
      val partList = parts(0L, objectSize, maxPartSize, List[String]()).reverse
      val uploadId = startUpload(destinationBucket, destinationKey)
      val uploadedParts = partList.zipWithIndex.map {
        case (part, index) => uploadPart(uploadId, index + 1, part)
      }
      val completedUpload = finishUpload(uploadId, uploadedParts.map(_._2))
      completedUpload
    }
  }
}

object MultipartUploader {
  def apply(s3Client: S3Client, maxPartSize: Long) =
    new MultipartUploader(s3Client, maxPartSize)
}
