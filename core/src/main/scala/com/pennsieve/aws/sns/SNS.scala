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

package com.pennsieve.aws.sns

import cats.data.EitherT
import software.amazon.awssdk.services.sns.model.{
  PublishRequest,
  PublishResponse
}
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.FutureEitherT
import com.pennsieve.domain.CoreError
import software.amazon.awssdk.services.sns.SnsAsyncClient

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

trait SNSClient {

  def publish(
    topicArn: String,
    message: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse]
}

/**
  * NOTE: this client uses v2 of the AWS SDK.
  */
class SNS(val client: SnsAsyncClient) extends SNSClient {

  override def publish(
    topicArn: String,
    message: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse] = {
    val request = {
      PublishRequest
        .builder()
        .topicArn(topicArn)
        .message(message)
        .build()
    }
    client.publish(request).toScala.toEitherT
  }
}
