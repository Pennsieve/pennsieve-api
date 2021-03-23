// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.aws.queue

import cats.data.EitherT
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  CreateQueueRequest,
  CreateQueueResponse,
  Message,
  PurgeQueueRequest,
  PurgeQueueResponse,
  SendMessageRequest,
  SendMessageResponse
}
import com.pennsieve.aws.AsyncHandler
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.FutureEitherT
import com.pennsieve.domain.CoreError

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

trait SQSClient {

  def send(
    queueUrl: String,
    message: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, SendMessageResponse]

  def createQueue(queueName: String): Future[CreateQueueResponse]

  def purgeQueue(queueUrl: String): Future[PurgeQueueResponse]

}

/**
  * NOTE: this client uses v2 of the AWS SDK.
  */
class SQS(val client: SqsAsyncClient) extends SQSClient {

  override def createQueue(queueName: String): Future[CreateQueueResponse] = {
    val request = CreateQueueRequest.builder().queueName(queueName).build()
    client.createQueue(request).toScala
  }

  override def purgeQueue(queueUrl: String): Future[PurgeQueueResponse] = {
    val request = PurgeQueueRequest.builder().queueUrl(queueUrl).build()
    client
      .purgeQueue(request)
      .toScala
  }

  override def send(
    queueUrl: String,
    message: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, SendMessageResponse] = {
    val request =
      SendMessageRequest
        .builder()
        .queueUrl(queueUrl)
        .messageBody(message)
        .build()
    client.sendMessage(request).toScala.toEitherT
  }
}
