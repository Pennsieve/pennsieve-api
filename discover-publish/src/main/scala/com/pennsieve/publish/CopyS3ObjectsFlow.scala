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

import akka.NotUsed
import akka.pattern.retry
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.{ MetaHeaders, S3Headers }
import akka.stream.scaladsl.Flow
import akka.stream.alpakka.s3.scaladsl._
import cats.data._
import cats.implicits._
import com.pennsieve.publish.models.{ CopyAction, FileAction }
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

/**
  * Copy objects in S3 from one bucket to another
  */
object CopyS3ObjectsFlow extends LazyLogging {

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

    val futureResult = S3
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
      .mapMaterializedValue(_.map(_ => copyAction))
      .run()

    logFutureResult(futureResult)
    futureResult
  }

  def fromUrl(copyAction: CopyAction): String =
    s"s3://${copyAction.file.s3Bucket}/${copyAction.file.s3Key}"

  def toUrl(copyAction: CopyAction): String =
    s"s3://${copyAction.toBucket}/${copyAction.copyToKey}"

  private def logFutureResult(
    future: Future[CopyAction]
  )(implicit
    ec: ExecutionContext
  ): Unit = {
    future.onComplete {
      case Success(action) =>
        logger.info(s"Done copying ${fromUrl(action)} to ${toUrl(action)}")
      case Failure(e) => logger.error(e.getMessage, e)
    }
  }
}
