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

package com.pennsieve.aws.lambda

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.{
  AWSLambdaAsync,
  AWSLambdaAsyncClientBuilder
}

import com.pennsieve.aws.LocalAWSCredentialsProvider

trait LambdaPort {
  def lambda: LambdaClient
}

class AWSLambdaPort(region: Regions) extends LambdaPort {
  override lazy val lambda: LambdaClient = new AWSLambdaClient(
    AWSLambdaAsyncClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()
  )
}

class LocalLambdaPort(host: String, region: Regions) extends LambdaPort {
  override lazy val lambda: LambdaClient = new AWSLambdaClient(
    AWSLambdaAsyncClientBuilder
      .standard()
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(host, region.getName)
      )
      .build()
  )
}
