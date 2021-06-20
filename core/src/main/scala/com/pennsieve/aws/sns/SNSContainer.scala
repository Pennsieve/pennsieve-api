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

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import com.pennsieve.core.utilities.CoreContainer
import com.pennsieve.utilities.Container
import net.ceedubs.ficus.Ficus._

import java.net.URI

trait SNSContainer { self: Container =>

  val snsHost: String = config.getAs[String]("sns.host").getOrElse("")
  val snsRegion: Region = config.getAs[String]("sns.region") match {
    case Some(region) => Region.of(region)
    case None => Region.US_EAST_1
  }

  val sns: SNS
}

trait AWSSNSContainer extends SNSContainer { self: Container =>

  override val sns: SNS = new SNS(
    SnsAsyncClient
      .builder()
      .region(snsRegion)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )
}

trait LocalSNSContainer extends SNSContainer { self: Container =>

  override val sns: SNS = new SNS(
    SnsAsyncClient
      .builder()
      .region(snsRegion)
      .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
      .endpointOverride(new URI(snsHost))
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )
}
