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

package com.pennsieve.aws.email

import com.pennsieve.email.client.EmailClient
import com.pennsieve.utilities.Container
import com.typesafe.scalalogging.Logger
import net.ceedubs.ficus.Ficus._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient

trait EmailContainer { self: Container =>

  def emailer: Emailer
}

trait AWSEmailContainer extends EmailContainer { self: Container =>

  lazy val emailServiceQueueUrl: String =
    config.as[String]("email.service_queue_url")

  lazy val emailClient: EmailClient =
    new EmailClient(
      SqsClient.builder().region(Region.US_EAST_1).build(),
      emailServiceQueueUrl
    )

  override lazy val emailer: Emailer = new QueueEmailer(emailClient)

  private lazy val emailLogger: Logger =
    Logger("com.pennsieve.aws.email.AWSEmailContainer")

  /** Fails at boot, rather than on the first send inside a request, if the
    * email-service queue URL is missing. Call it once on the long-lived
    * insecure container, never on the per-request secure containers.
    */
  def verifyEmailConfig(): Unit = {
    locally(emailer)
    emailLogger.info(
      s"Sending platform emails through the email-service queue $emailServiceQueueUrl"
    )
  }
}

trait LocalEmailContainer extends EmailContainer { self: Container =>
  override lazy val emailer: Emailer = new LoggingEmailer
}
