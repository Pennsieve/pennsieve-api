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

package com.pennsieve.helpers

import cats.data.EitherT
import cats.syntax.either.catsSyntaxEitherId
import software.amazon.awssdk.services.sqs.model.{
  CreateQueueResponse,
  PurgeQueueResponse,
  SendMessageResponse
}
import com.pennsieve.aws.queue.SQSClient
import com.pennsieve.domain.CoreError

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
