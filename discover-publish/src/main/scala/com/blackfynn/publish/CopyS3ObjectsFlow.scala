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
import akka.stream.scaladsl.Flow
import akka.stream.alpakka.s3.scaladsl._
import cats.data._
import cats.implicits._
import com.pennsieve.publish.models.{ CopyAction, S3Action }
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
  ): Flow[S3Action, S3Action, NotUsed] = {
    implicit val scheduler = system.scheduler

    Flow[S3Action]
      .mapAsyncUnordered(container.s3CopyFileParallelism)(
        copyAction =>
          copyAction match {
            case ca: CopyAction =>
              retry(() => copyFile(ca), attempts = 5, delay = 5.second)
            case x: S3Action => Future(x)
          }
      )
  }

  def copyFile(
    copyAction: CopyAction
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[S3Action] = {
    logger
      .info(
        s"Copying ${fromUrl(copyAction)} to ${toUrl(copyAction)} with copyAction.s3version being: ${copyAction.s3version}"
      )

    val futureResult = S3
      .multipartCopy(
        sourceBucket = copyAction.file.s3Bucket,
        sourceKey = copyAction.file.s3Key,
        targetBucket = copyAction.toBucket,
        targetKey = copyAction.copyToKey,
        chunkSize = container.s3CopyChunkSize,
        chunkingParallelism = container.s3CopyChunkParallelism
      )
      .mapMaterializedValue(_.map(result => {
        copyAction.copy(s3version = result.versionId)
      }))
      .run()

    futureResult.onComplete {
      case Success(_) =>
        logger.info(
          s"Done copying ${fromUrl(copyAction)} to ${toUrl(copyAction)} with copyAction.s3version being: ${copyAction.s3version}"
        )
      case Failure(e) => logger.error(e.getMessage, e)
    }

    futureResult
  }

  def fromUrl(action: S3Action): String =
    s"s3://${action.file.s3Bucket}/${action.file.s3Key}"

  def toUrl(copyAction: CopyAction): String =
    s"s3://${copyAction.toBucket}/${copyAction.copyToKey}"
}
