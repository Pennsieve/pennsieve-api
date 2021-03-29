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

package com.pennsieve.aws.s3

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.pennsieve.aws.LocalAWSCredentialsProvider

trait S3Port {
  lazy val clientConfiguration: ClientConfiguration =
    new ClientConfiguration()
      .withSignerOverride("AWSS3V4SignerType")

  val client: AmazonS3
}

class AWSS3Port(region: Regions) extends S3Port {

  override lazy val client: AmazonS3 =
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(clientConfiguration)
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()

}

class LocalS3Port(host: String, region: Regions) extends S3Port {

  override lazy val client: AmazonS3 =
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(clientConfiguration)
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(host, region.getName)
      )
      .withPathStyleAccessEnabled(true)
      .build()

}
