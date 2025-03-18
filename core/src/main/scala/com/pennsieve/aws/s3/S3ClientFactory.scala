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
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }

import scala.collection.concurrent.TrieMap

object S3ClientFactory {

  private val s3ClientsMap: TrieMap[String, S3] = new TrieMap[String, S3]()

  private val regionMappings: Map[String, String] = Map(
    "use1" -> "us-east-1",
    "afs1" -> "af-south-1",
    "use2" -> "us-east-2",
    "usw1" -> "us-west-1",
    "usw2" -> "us-west-2"
    // Add more regions
  )
  def getClientForBucket(bucket: String): S3 = {
    val region = regionMappings
      .collectFirst {
        case (suffix, region) if bucket.endsWith(suffix) => region
      }
      .getOrElse("us-east-1")

    s3ClientsMap.getOrElseUpdate(region, {
      val awsS3Client = createS3Client(region)
      new S3(awsS3Client)
    })
  }
  private def createS3Client(region: String): AmazonS3 = {
    val regionalConfig =
      new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(regionalConfig)
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()
  }
}
