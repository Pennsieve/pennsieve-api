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

package com.pennsieve.aws.queue

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }

import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import com.pennsieve.utilities.Container
import net.ceedubs.ficus.Ficus._

import java.net.URI

trait SQSContainer { self: Container =>

  val sqs: SQS

  val sqs_host: String = config.as[String]("sqs.host")
  val sqs_region: Region = Region.of(config.as[String]("sqs.region"))
}

trait AWSSQSContainer extends SQSContainer { self: Container =>

  override lazy val sqs: SQS = new SQS(
    SqsAsyncClient
      .builder()
      .region(sqs_region)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )

}

trait LocalSQSContainer extends SQSContainer { self: Container =>

  override lazy val sqs: SQS = new SQS(
    SqsAsyncClient
      .builder()
      .region(sqs_region)
      .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
      .endpointOverride(new URI(sqs_host))
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )
}
