// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.uploads.consumer

import com.blackfynn.models._
import com.blackfynn.test.helpers.EitherValue._
import io.circe.syntax._
import io.circe.java8.time._
import java.util.UUID

import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.blackfynn.akka.consumer.ProcessorUtilities
import com.blackfynn.models
import org.scalatest.{ Matchers, WordSpec }

class ProcessorSpec extends WordSpec with Matchers {

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
