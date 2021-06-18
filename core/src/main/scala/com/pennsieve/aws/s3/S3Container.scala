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

import com.pennsieve.utilities.Container
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.pennsieve.aws.LocalAWSCredentialsProvider
import net.ceedubs.ficus.Ficus._

trait S3Container { self: Container =>

  val s3_host: String = config.as[String]("s3.host")
  val s3_region: Regions = config.as[Option[String]]("s3.region") match {
    case Some(region) => Regions.fromName(region)
    case None => Regions.US_EAST_1
  }

  lazy val s3ClientConfiguration: ClientConfiguration =
    new ClientConfiguration()
      .withSignerOverride("AWSS3V4SignerType")

  val s3: S3Trait

}

trait AWSS3Container extends S3Container { self: Container =>

  override lazy val s3: S3 = new S3(
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(s3ClientConfiguration)
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(s3_region)
      .build()
  )

}

trait LocalS3Container extends S3Container { self: Container =>

  override lazy val s3: S3 = new S3(
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(s3ClientConfiguration)
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(s3_host, s3_region.getName)
      )
      .withPathStyleAccessEnabled(true)
      .build()
  )

}
