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

import cats.implicits._
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3URI }
import com.amazonaws.services.s3.model.{
  Bucket,
  CannedAccessControlList,
  CompleteMultipartUploadRequest,
  CompleteMultipartUploadResult,
  CopyObjectRequest,
  CopyObjectResult,
  CopyPartRequest,
  CopyPartResult,
  GeneratePresignedUrlRequest,
  HeadBucketRequest,
  HeadBucketResult,
  InitiateMultipartUploadRequest,
  InitiateMultipartUploadResult,
  ListObjectsRequest,
  ObjectListing,
  ObjectMetadata,
  PartETag,
  PutObjectRequest,
  PutObjectResult,
  S3Object,
  S3ObjectSummary
}
import java.io.{ File, InputStream }
import java.net.URL

import collection.JavaConverters._
import scala.annotation.tailrec

trait S3Trait {
  def getObject(s3URI: AmazonS3URI): Either[Throwable, S3Object]
  def getObject(bucket: String, key: String): Either[Throwable, S3Object]
  def getObjectMetadata(s3URI: AmazonS3URI): Either[Throwable, ObjectMetadata]

  def getObjectMetadata(
    bucket: String,
    key: String
  ): Either[Throwable, ObjectMetadata]

  def deleteObject(bucket: String, key: String): Either[Throwable, Unit]

  def deleteObject(o: S3Object): Either[Throwable, Unit] =
    deleteObject(o.getBucketName, o.getKey)

  def copyObject(
    request: CopyObjectRequest
  ): Either[Throwable, CopyObjectResult]

  def copyPart(request: CopyPartRequest): Either[Throwable, CopyPartResult]

  def putObject(
    bucket: String,
    key: String,
    file: File
  ): Either[Throwable, PutObjectResult]

  def putObject(
    bucket: String,
    key: String,
    input: InputStream,
    metadata: ObjectMetadata
  ): Either[Throwable, PutObjectResult]

  def putObject(
    bucket: String,
    key: String,
    content: String
  ): Either[Throwable, PutObjectResult]

  def putObject(
    putRequest: PutObjectRequest
  ): Either[Throwable, PutObjectResult]

  def createBucket(bucket: String): Either[Throwable, Bucket]

  def deleteBucket(bucket: String): Either[Throwable, Unit]

  def initiateMultipartUpload(
    request: InitiateMultipartUploadRequest
  ): Either[Throwable, InitiateMultipartUploadResult]

  def completeMultipartUpload(
    request: CompleteMultipartUploadRequest
  ): Either[Throwable, CompleteMultipartUploadResult]

  def generatePresignedUrl(
    request: GeneratePresignedUrlRequest
  ): Either[Throwable, URL]

  def headBucket(bucket: String): Either[Throwable, HeadBucketResult]

  def doesObjectExist(
    bucketName: String,
    objectName: String
  ): Either[Throwable, Boolean]

  private final val FiveMegabytes = 5 * 1024L * 1024L
  private final val OneGigabyte = 1024L * 1024L * 1024L
  private final val FiveGigabytes = 5 * OneGigabyte

  /**
    * Copy files larger than the S3 limit of 5GB
    *
    * TODO: test ACL parameters with LocalStack.
    */
  def multipartCopy(
    sourceBucket: String,
    sourceKey: String,
    destinationBucket: String,
    destinationKey: String,
    acl: Option[CannedAccessControlList] = None,
    multipartChunkSize: Long = OneGigabyte,
    multipartCopyLimit: Long = FiveGigabytes // Use multipart copy for objects larger than this
  ): Either[Throwable, Either[
    CopyObjectResult,
    CompleteMultipartUploadResult
  ]] = {
    require(multipartCopyLimit >= FiveMegabytes, "S3 min part size is 5 Mb")
    require(multipartCopyLimit <= FiveGigabytes, "S3 max part size is 5 Gb")
    require(multipartChunkSize >= FiveMegabytes, "S3 min part size is 5 Mb")
    require(multipartChunkSize <= FiveGigabytes, "S3 max part size is 5 Gb")

    getObjectMetadata(sourceBucket, sourceKey).flatMap { metadata =>
      val length: Long = metadata.getContentLength

      // asset is smaller than 5 GB, use simple copy object request
      if (length < multipartCopyLimit) {
        val request: CopyObjectRequest =
          acl.foldLeft(
            new CopyObjectRequest(
              sourceBucket,
              sourceKey,
              destinationBucket,
              destinationKey
            )
          )(_.withCannedAccessControlList(_))

        copyObject(request).map(_.asLeft)

        // asset is larger than 5 GB, use multipart copy request
      } else {
        for {
          uploadId <- initiateMultipartUpload(
            acl.foldLeft(
              new InitiateMultipartUploadRequest(
                destinationBucket,
                destinationKey
              ).withObjectMetadata(metadata)
            )(_.withCannedACL(_))
          ).map(_.getUploadId())

          // Compute number of multipart chunks
          parts = if ((length % multipartChunkSize) > 0)
            (length / multipartChunkSize) + 1
          else
            length / multipartChunkSize

          partETags <- (1 to parts.intValue)
            .map { part =>
              val firstByte: Long = (part - 1) * multipartChunkSize

              // The last part might be smaller than partSize, so check to make sure
              // that lastByte isn't beyond the end of the object.
              val lastByte: Long = Math
                .min(firstByte + multipartChunkSize - 1, length - 1) // zero-indexed bytes

              copyPart(
                new CopyPartRequest()
                  .withSourceBucketName(sourceBucket)
                  .withSourceKey(sourceKey)
                  .withDestinationBucketName(destinationBucket)
                  .withDestinationKey(destinationKey)
                  .withUploadId(uploadId)
                  .withFirstByte(firstByte)
                  .withLastByte(lastByte)
                  .withPartNumber(part)
              ).map { result =>
                new PartETag(result.getPartNumber, result.getETag)
              }
            }
            .toList
            .sequence

          complete <- completeMultipartUpload(
            new CompleteMultipartUploadRequest(
              destinationBucket,
              destinationKey,
              uploadId,
              partETags.asJava
            )
          )
        } yield complete.asRight
      }
    }
  }
}

class S3(val client: AmazonS3) extends S3Trait {

  def getObject(s3URI: AmazonS3URI): Either[Throwable, S3Object] =
    Either.catchNonFatal(client.getObject(s3URI.getBucket, s3URI.getKey))

  def getObject(bucket: String, key: String): Either[Throwable, S3Object] =
    Either.catchNonFatal { client.getObject(bucket, key) }

  def getObjectMetadata(s3URI: AmazonS3URI): Either[Throwable, ObjectMetadata] =
    Either.catchNonFatal {
      client.getObjectMetadata(s3URI.getBucket, s3URI.getKey)
    }

  def getObjectMetadata(
    bucket: String,
    key: String
  ): Either[Throwable, ObjectMetadata] =
    Either.catchNonFatal { client.getObjectMetadata(bucket, key) }

  def deleteObject(bucket: String, key: String): Either[Throwable, Unit] =
    Either.catchNonFatal { client.deleteObject(bucket, key) }

  def deleteObjectsByPrefix(
    bucket: String,
    keyPrefix: String
  ): Either[Throwable, Unit] = Either.catchNonFatal {
    // Adapted from https://stackoverflow.com/questions/42442259/delete-a-folder-and-its-content-aws-s3-java
    val listRequest: ListObjectsRequest = new ListObjectsRequest()
      .withBucketName(bucket)
      .withRequesterPays(true)
      .withPrefix(if (keyPrefix.endsWith("/")) {
        keyPrefix
      } else {
        keyPrefix + "/"
      })

    @tailrec
    def iter(listing: ObjectListing): Unit = {
      val summaries: Seq[S3ObjectSummary] = listing.getObjectSummaries.asScala
      for (summary <- summaries) {
        client.deleteObject(bucket, summary.getKey)
      }
      if (listing.isTruncated) {
        iter(client.listNextBatchOfObjects(listing))
      }
    }

    iter(client.listObjects(listRequest))
  }

  def objectSummaries(
    bucket: String,
    prefix: String
  ): Either[Throwable, List[S3ObjectSummary]] = {

    val listRequest: ListObjectsRequest = new ListObjectsRequest()
      .withBucketName(bucket)
      .withRequesterPays(true)
      .withPrefix(if (prefix.endsWith("/")) {
        prefix
      } else {
        prefix + "/"
      })

    @tailrec
    def iter(
      listing: ObjectListing,
      results: List[S3ObjectSummary]
    ): List[S3ObjectSummary] = {

      val summaries: List[S3ObjectSummary] =
        results ::: listing.getObjectSummaries.asScala.toList

      if (listing.isTruncated) {
        iter(client.listNextBatchOfObjects(listing), summaries)
      } else {
        summaries
      }
    }

    Either.catchNonFatal { iter(client.listObjects(listRequest.withRequesterPays(true)), List.empty) }
  }

  def copyObject(
    request: CopyObjectRequest
  ): Either[Throwable, CopyObjectResult] =
    Either.catchNonFatal { client.copyObject(request.withRequesterPays(true)) }

  def copyPart(request: CopyPartRequest): Either[Throwable, CopyPartResult] =
    Either.catchNonFatal { client.copyPart(request.withRequesterPays(true)) }

  def putObject(
    bucket: String,
    key: String,
    file: File
  ): Either[Throwable, PutObjectResult] =
    Either.catchNonFatal { client.putObject(bucket, key, file) }

  def putObject(
    bucket: String,
    key: String,
    input: InputStream,
    metadata: ObjectMetadata
  ): Either[Throwable, PutObjectResult] =
    Either.catchNonFatal { client.putObject(bucket, key, input, metadata) }

  def putObject(
    bucket: String,
    key: String,
    content: String
  ): Either[Throwable, PutObjectResult] =
    Either.catchNonFatal { client.putObject(bucket, key, content) }

  def putObject(
    putRequest: PutObjectRequest
  ): Either[Throwable, PutObjectResult] =
    Either.catchNonFatal { client.putObject(putRequest.withRequesterPays(true)) }


  def createBucket(bucket: String): Either[Throwable, Bucket] =
    Either.catchNonFatal { client.createBucket(bucket) }

  def deleteBucket(bucket: String): Either[Throwable, Unit] =
    Either.catchNonFatal { client.deleteBucket(bucket) }

  def initiateMultipartUpload(
    request: InitiateMultipartUploadRequest
  ): Either[Throwable, InitiateMultipartUploadResult] =
    Either.catchNonFatal { client.initiateMultipartUpload(request.withRequesterPays(true)) }

  def completeMultipartUpload(
    request: CompleteMultipartUploadRequest
  ): Either[Throwable, CompleteMultipartUploadResult] =
    Either.catchNonFatal { client.completeMultipartUpload(request.withRequesterPays(true)) }

  def generatePresignedUrl(
    request: GeneratePresignedUrlRequest
  ): Either[Throwable, URL] =
    Either.catchNonFatal {
      client.generatePresignedUrl(request)
    }

  def headBucket(bucket: String): Either[Throwable, HeadBucketResult] =
    Either.catchNonFatal {
      client.headBucket(new HeadBucketRequest(bucket))
    }

  def doesObjectExist(
    bucketName: String,
    objectName: String
  ): Either[Throwable, Boolean] =
    Either.catchNonFatal {
      client.doesObjectExist(bucketName, objectName)
    }
}
