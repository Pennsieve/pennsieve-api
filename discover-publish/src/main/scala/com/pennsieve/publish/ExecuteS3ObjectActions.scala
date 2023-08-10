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

import akka.{ Done, NotUsed }
import akka.pattern.retry
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.{
  DeleteMarkers,
  ListObjectVersionsResultVersions,
  S3Headers
}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import com.pennsieve.publish.CopyS3ObjectsFlow.logger
import com.pennsieve.publish.PublishContainer
import com.pennsieve.publish.models.{
  CopyAction,
  DeleteAction,
  FileAction,
  KeepAction
}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object ExecuteS3ObjectActions extends LazyLogging {
  def apply(
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    system: ActorSystem
  ): Flow[FileAction, FileAction, NotUsed] = {
    implicit val scheduler = system.scheduler

    Flow[FileAction]
      .mapAsyncUnordered(container.s3CopyFileParallelism)(
        fileAction =>
          fileAction match {
            case copyAction: CopyAction =>
              retry(() => copyFile(copyAction), attempts = 5, delay = 5.second)
            case deleteAction: DeleteAction =>
              retry(
                () => deleteFile(deleteAction),
                attempts = 5,
                delay = 5.second
              )
            case keepAction: KeepAction =>
              retry(() => keepFile(keepAction), attempts = 5, delay = 5.second)
          }
      )
  }

  def copyFile(
    copyAction: CopyAction
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[CopyAction] = {
    logger
      .info(s"Copying ${fromUrl(copyAction)} to ${toUrl(copyAction)}")

    val multipartCopyF = S3
      .multipartCopy(
        sourceBucket = copyAction.file.s3Bucket,
        sourceKey = copyAction.file.s3Key,
        targetBucket = copyAction.toBucket,
        targetKey = copyAction.copyToKey,
        chunkSize = container.s3CopyChunkSize,
        chunkingParallelism = container.s3CopyChunkParallelism,
        s3Headers = S3Headers()
          .withCustomHeaders(Map("x-amz-request-payer" -> "requester"))
      )
      //.mapMaterializedValue(_.map(_ => copyAction))
      .mapMaterializedValue(result => result)
      .run()

    for {
      multipartUploadResult <- multipartCopyF
      _ = logCopyResult(copyAction)
    } yield (copyAction.copy(s3VersionId = multipartUploadResult.versionId))
  }

  def deleteFile(
    deleteAction: DeleteAction
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[DeleteAction] = {
    logger
      .info(s"Deleting ${deleteUrl(deleteAction)}")

    val deleteObjectF = S3
      .deleteObject(
        bucket = deleteAction.fromBucket,
        key = s"${deleteAction.baseKey}/${deleteAction.fileKey}",
        versionId = None,
        s3Headers = S3Headers()
          .withCustomHeaders(Map("x-amz-request-payer" -> "requester"))
      )
      .run()

    logDeleteComplete(deleteObjectF, deleteAction)

    // execute a list-object-versions, get the DeleteMarker from the response
    val listObjectVersionsF = S3
      .listObjectVersions(
        bucket = deleteAction.fromBucket,
        prefix = Some(s"${deleteAction.baseKey}/${deleteAction.fileKey}"),
        s3Headers = S3Headers()
          .withCustomHeaders(Map("x-amz-request-payer" -> "requester"))
      )
      .toMat(accumulateListObjectVersions())(Keep.right)
      .run()

    for {
      deleteMarkers <- listObjectVersionsF
      // TODO: ensure we get the most recent DeleteMarker
      deletedAction = deleteAction.copy(
        s3VersionId = deleteMarkers.head.versionId
      )
    } yield deletedAction
  }

  def keepFile(
    keepAction: KeepAction
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[KeepAction] = {
    logger
      .info(s"Keeping ${keepUrl(keepAction)}")

    Future.successful(keepAction)
  }

  def fromUrl(action: CopyAction): String =
    s"s3://${action.file.s3Bucket}/${action.file.s3Key}"

  def toUrl(action: CopyAction): String =
    s"s3://${action.toBucket}/${action.copyToKey}"

  def deleteUrl(action: DeleteAction): String =
    s"s3://${action.fromBucket}/${action.baseKey}/${action.fileKey}"

  def keepUrl(action: KeepAction): String =
    s"s3://${action.bucket}/${action.baseKey}/${action.fileKey}"

  private def logCopyResult(
    action: CopyAction
  )(implicit
    ec: ExecutionContext
  ): Unit =
    logger.info(s"Done copying ${fromUrl(action)} to ${toUrl(action)}")

  private def logDeleteComplete(
    future: Future[Done],
    deleteAction: DeleteAction
  )(implicit
    ec: ExecutionContext
  ): Unit = {
    future.onComplete {
      case Success(action) =>
        logger.info(s"Done deleting ${deleteUrl(deleteAction)}")
      case Failure(e) => logger.error(e.getMessage, e)
    }
  }

  def accumulateListObjectVersions(
  ): Sink[(Seq[ListObjectVersionsResultVersions], Seq[DeleteMarkers]), Future[
    List[DeleteMarkers]
  ]] =
    Sink.fold(Nil: List[DeleteMarkers])(
      (
        accum,
        response: (Seq[ListObjectVersionsResultVersions], Seq[DeleteMarkers])
      ) => response._2.filter(dm => dm.isLatest).head :: accum
    )
}
