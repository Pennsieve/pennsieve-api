package com.blackfynn.helpers

import cats.data.EitherT
import cats.syntax.either.catsSyntaxEitherId
import software.amazon.awssdk.services.sqs.model.{
  CreateQueueResponse,
  PurgeQueueResponse,
  SendMessageResponse
}
import com.blackfynn.aws.queue.SQSClient
import com.blackfynn.domain.CoreError

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

object MockSQSClient extends SQSClient {

  val sentMessages: mutable.Map[String, List[String]] = mutable.Map.empty

  override def send(
    queueUrl: String,
    messageBody: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, SendMessageResponse] = {
    sentMessages
      .update(
        key = queueUrl,
        value = messageBody :: sentMessages
          .getOrElse(queueUrl, List.empty[String])
      )

    EitherT(
      Future.successful(
        SendMessageResponse
          .builder()
          .md5OfMessageBody("ABCDE1234")
          .messageId("77297425")
          .build()
          .asRight[CoreError]
      )
    )
  }

  override def createQueue(queueName: String): Future[CreateQueueResponse] = ???

  override def purgeQueue(queueUrl: String): Future[PurgeQueueResponse] = ???

}
