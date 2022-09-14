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

package com.pennsieve.uploads.consumer

import com.pennsieve.models._
import org.scalatest.EitherValues._
import io.circe.syntax._
import io.circe.java8.time._

import java.util.UUID
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.pennsieve.akka.consumer.ProcessorUtilities
import com.pennsieve.models
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProcessorSpec extends AnyWordSpec with Matchers {

  val importId: JobId = new JobId(UUID.randomUUID)
  val packageId: Int = 1
  val datasetId: Int = 1
  val userId: Int = 1
  val organizationId: Int = 1

  val encryptionKey: String = "test-encryption-key"
  val packageNodeId: String = s"N:package:${UUID.randomUUID}"
  val uploadDirectory: String = s"test@pennsieve.org/$importId/"
  val storageDirectory: String = s"test@pennsieve.org/data/$importId/"

  "Processor.parser" should {
    "be able to parse an upload manifest from SQS" in {
      val payload =
        Upload(
          packageId,
          datasetId,
          userId,
          encryptionKey,
          List(s"${uploadDirectory}test.csv"),
          100L
        )

      val sentManifest =
        Manifest(PayloadType.Upload, importId, organizationId, payload)

      val message =
        SQSMessage.builder().body(sentManifest.asJson.toString).build()
      val (_, createdManifest) =
        ProcessorUtilities.parser[models.Manifest](message).value

      createdManifest shouldBe sentManifest
    }
  }
}
