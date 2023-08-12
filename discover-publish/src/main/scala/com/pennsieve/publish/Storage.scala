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

import akka.actor.ActorSystem
import cats.data.EitherT
import com.amazonaws.services.s3.model.{ ObjectMetadata, PutObjectRequest }
import com.pennsieve.domain.{ CoreError, ThrowableError }
import com.pennsieve.publish.Publish.dropNullPrinter
import com.pennsieve.publish.utils.joinKeys
import io.circe.Json
import cats.data._
import cats.implicits._
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.{ ExecutionContext, Future }

object Storage {
  val PUBLISH_ASSETS_FILENAME: String = "publish.json"

  val GRAPH_ASSETS_FILENAME: String = "graph.json"

  val OUTPUT_FILENAME: String = "outputs.json"

  val README_FILENAME: String = "readme.md"

  val CHANGELOG_FILENAME: String = "changelog.md"

  val BANNER_FILENAME: String = "banner.jpg"

  val METADATA_FILENAME: String = "manifest.json"

  val FILE_ACTIONS_FILENAME: String = "file-actions.json"

  private def assetKey(
    containerConfig: PublishContainerConfig,
    assetFilename: String
  ): String =
    containerConfig.workflowId match {
      case PublishingWorkflows.Version5 =>
        joinKeys(
          Seq(containerConfig.publishedDatasetId.toString, assetFilename)
        )
      case _ =>
        joinKeys(
          Seq(
            containerConfig.publishedDatasetId.toString,
            containerConfig.version.toString,
            assetFilename
          )
        )
    }

  def publishedAssetsKey(config: PublishContainerConfig): String =
    assetKey(config, PUBLISH_ASSETS_FILENAME)

  def graphManifestKey(config: PublishContainerConfig): String =
    assetKey(config, GRAPH_ASSETS_FILENAME)

  def outputKey(config: PublishContainerConfig): String =
    assetKey(config, OUTPUT_FILENAME)

  def publishedMetadataKey(config: PublishContainerConfig): String =
    assetKey(config, METADATA_FILENAME)

  def fileActionsKey(config: PublishContainerConfig): String =
    assetKey(config, FILE_ACTIONS_FILENAME)

  /**
    * Perform a blocking, single-part upload to S3.
    */
  def uploadToS3(
    containerConfig: PublishContainerConfig,
    key: String,
    payload: Json
  )(implicit
    executionContext: ExecutionContext,
    system: ActorSystem
  ): EitherT[Future, CoreError, Unit] = {

    val payloadBytes = dropNullPrinter(payload)
      .getBytes(StandardCharsets.UTF_8)
    val payloadInputStream: ByteArrayInputStream = new ByteArrayInputStream(
      payloadBytes
    )
    val metadata = new ObjectMetadata()
    metadata.setContentLength(payloadBytes.length)
    EitherT
      .fromEither[Future](
        containerConfig.s3
          .putObject(
            new PutObjectRequest(
              containerConfig.s3Bucket,
              key,
              payloadInputStream,
              metadata
            )
          )
          .leftMap(ThrowableError)
          .map(_ => ())
      )
  }
}
