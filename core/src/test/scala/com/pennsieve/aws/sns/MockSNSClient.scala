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
import cats.implicits.catsSyntaxEitherId
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import com.pennsieve.domain.CoreError
import com.pennsieve.utilities.Container
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.{
  PublishRequest,
  PublishResponse
}

import java.net.URI
import scala.concurrent.{ ExecutionContext, Future }

class MockSNS() extends SNSClient {

  val client: SnsAsyncClient = SnsAsyncClient
    .builder()
    .region(Region.US_EAST_1)
    .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
    .endpointOverride(new URI(s"http://localhost"))
    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
    .build()

  override def publish(
    topicArn: String,
    message: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, PublishResponse] = {

    EitherT(
      Future.successful(
        PublishResponse
          .builder()
          .messageId("77297425")
          .build()
          .asRight[CoreError]
      )
    )
  }
}
