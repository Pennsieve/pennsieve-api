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

/** What makes one email a distinct event for the email-service's dedupe.
  *
  * The email-service suppresses a repeat of the same dedupe id to the same
  * recipient for 90 days. Without one it hashes messageId + recipient + the
  * template values, which wrongly suppresses an email that is legitimately
  * sent again with the same values (a second revision of a dataset, the same
  * person re-added to a team). So every send names its event explicitly.
  */
object EmailEventKey {

  /** For emails sent when a publication status row is created or completed:
    * a redelivery of the same callback reuses the same row, a new submission
    * or revision creates a new one.
    */
  def forPublicationStatus(id: Int): String = s"publication-status-$id"

  /** For emails sent from a single user-initiated request that has no
    * durable id and is not redelivered: every request is its own event.
    */
  def unique(): String = java.util.UUID.randomUUID().toString
}

trait Emailer {

  /** @param eventKey identifies the event that caused this email; see
    *                 [[EmailEventKey]]. Combined with the messageId into the
    *                 email-service dedupe id.
    */
  def send(
    request: EmailRequest,
    eventKey: String
  ): Either[Throwable, EmailSendResult]
}

object Emailer {
  def dedupeId(request: EmailRequest, eventKey: String): String =
    s"${request.messageId}:$eventKey"
}

/** Enqueues the request on the email-service send queue; the email-service
  * renders the template and delivers it.
  */
class QueueEmailer(client: EmailClient) extends Emailer {
  def send(
    request: EmailRequest,
    eventKey: String
  ): Either[Throwable, EmailSendResult] =
    client
      .send(request.withDedupeId(Emailer.dedupeId(request, eventKey)))
      .toEither
      .map(EmailSendResult(_))
}

class LoggingEmailer extends Emailer with LazyLogging {
  val sentEmails: mutable.ArrayBuffer[EmailRequest] = mutable.ArrayBuffer.empty

  /** Test hook: when set, `send` fails like an enqueue failure would. */
  @volatile var failSends: Option[Throwable] = None

  def send(
    request: EmailRequest,
    eventKey: String
  ): Either[Throwable, EmailSendResult] =
    failSends match {
      case Some(error) => Left(error)
      case None =>
        sentEmails += request.withDedupeId(Emailer.dedupeId(request, eventKey))
        logger.debug("Not sending test email since ENVIRONMENT is LOCAL")
        logger.debug("messageId: " + request.messageId)
        logger.debug("to: " + request.recipients.map(_.email).mkString(", "))
        logger.debug("context: " + request.context)
        Right(EmailSendResult("testmessage"))
    }

  def sendEmailTo(recipient: String): Boolean =
    sentEmails.exists(_.recipients.exists(_.email.equalsIgnoreCase(recipient)))
}
