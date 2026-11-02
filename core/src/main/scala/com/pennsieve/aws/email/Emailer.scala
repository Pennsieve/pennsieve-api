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

import com.pennsieve.email.client.{ EmailClient, EmailRequest }
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

/** The id of the message enqueued on the email-service send queue. Delivery is
  * asynchronous: this means the request was accepted, not that it was sent.
  */
case class EmailSendResult(id: String)

// TODO: move to models
case class Email(address: String) extends AnyVal {
  override def toString: String = address
}

trait Emailer {
  def send(request: EmailRequest): Either[Throwable, EmailSendResult]
}

/** Enqueues the request on the email-service send queue; the email-service
  * renders the template and delivers it.
  */
class QueueEmailer(client: EmailClient) extends Emailer {
  def send(request: EmailRequest): Either[Throwable, EmailSendResult] =
    client.send(request).toEither.map(EmailSendResult(_))
}

class LoggingEmailer extends Emailer with LazyLogging {
  val sentEmails: mutable.ArrayBuffer[EmailRequest] = mutable.ArrayBuffer.empty

  def send(request: EmailRequest): Either[Throwable, EmailSendResult] = {
    sentEmails += request
    logger.debug("Not sending test email since ENVIRONMENT is LOCAL")
    logger.debug("messageId: " + request.messageId)
    logger.debug("to: " + request.recipients.map(_.email).mkString(", "))
    logger.debug("context: " + request.context)
    Right(EmailSendResult("testmessage"))
  }

  def sendEmailTo(recipient: String): Boolean =
    sentEmails.exists(_.recipients.exists(_.email.equalsIgnoreCase(recipient)))
}
