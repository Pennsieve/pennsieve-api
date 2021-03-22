// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.email

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService
import com.amazonaws.services.simpleemail.model.{
  Body,
  Content,
  Destination,
  Message,
  SendEmailRequest
}
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try
import scala.collection.mutable

case class SesMessageResult(id: String)

case class Email(address: String) extends AnyVal {
  override def toString: String = address
}

case class EmailToSend(to: Email, from: Email, message: String, subject: String)

trait Emailer {
  def sendEmail(
    to: Email,
    from: Email,
    message: String,
    subject: String
  ): Either[Throwable, SesMessageResult]

  def sendEmail(toSend: EmailToSend): Either[Throwable, SesMessageResult]
}

class SESEmailer(client: AmazonSimpleEmailService) extends Emailer {

  def sendEmail(toSend: EmailToSend): Either[Throwable, SesMessageResult] =
    sendEmail(toSend.to, toSend.from, toSend.message, toSend.subject)

  def sendEmail(
    to: Email,
    from: Email,
    message: String,
    subject: String
  ): Either[Throwable, SesMessageResult] = {
    Try {
      // Construct an object to contain the recipient address.
      val destination = new Destination().withToAddresses(to.address)

      // Create the subject and body of the message.
      val message_subject = new Content().withData(subject)
      val message_textBody = new Content().withData(message)
      val message_body = new Body().withHtml(message_textBody)

      // Create a message with the specified subject and body.
      val m = new Message().withSubject(message_subject).withBody(message_body)

      // Assemble the email.
      val request = new SendEmailRequest()
        .withSource(from.address)
        .withDestination(destination)
        .withMessage(m)

      val result = client.sendEmail(request)
      SesMessageResult(result.getMessageId)
    }.toEither
  }
}

class LoggingEmailer extends Emailer with LazyLogging {
  val sentEmails: mutable.ArrayBuffer[EmailToSend] = mutable.ArrayBuffer.empty

  def sendEmail(toSend: EmailToSend): Either[Throwable, SesMessageResult] = {
    sentEmails += toSend
    logger.debug("Not sending test email since ENVIRONMENT is LOCAL")
    logger.debug("from: " + toSend.from.address)
    logger.debug("to: " + toSend.to.address)
    logger.debug("subject: " + toSend.subject)
    logger.debug("message: " + toSend.message)
    Right(SesMessageResult("testmessage"))
  }

  def sendEmail(
    to: Email,
    from: Email,
    message: String,
    subject: String
  ): Either[Throwable, SesMessageResult] =
    sendEmail(EmailToSend(to, from, message, subject))
}
