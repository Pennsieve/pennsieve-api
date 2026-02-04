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
import com.amazonaws.auth.{
  AWSCredentialsProvider,
  DefaultAWSCredentialsProviderChain,
  STSAssumeRoleSessionCredentialsProvider
}
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder

import scala.collection.concurrent.TrieMap

object S3ClientFactory {

  private val s3ClientsMap: TrieMap[String, S3] = new TrieMap[String, S3]()
  private val externalBucketClientsMap: TrieMap[String, S3] =
    new TrieMap[String, S3]()

  // Configuration for external bucket -> IAM role mapping
  // This should be populated from application configuration at startup
  @volatile private var externalBucketToRole: Map[String, String] = Map.empty

  /**
    * Configure the mapping of external buckets to IAM role ARNs.
    * This should be called at application startup.
    *
    * @param bucketToRole Map of bucket name to IAM role ARN
    */
  def configureExternalBuckets(bucketToRole: Map[String, String]): Unit = {
    externalBucketToRole = bucketToRole
  }

  private val regionMappings: Map[String, String] = Map(
    "use1" -> "us-east-1",
    "afs1" -> "af-south-1",
    "use2" -> "us-east-2",
    "usw1" -> "us-west-1",
    "usw2" -> "us-west-2"
    // Add more regions
  )

  /**
    * Get the region for a bucket based on its name suffix.
    */
  def getRegionForBucket(bucket: String): String = {
    regionMappings
      .collectFirst {
        case (suffix, region) if bucket.endsWith(suffix) => region
      }
      .getOrElse("us-east-1")
  }

  /**
    * Get an S3 client for the given bucket.
    * If the bucket is configured as an external bucket with a role ARN,
    * returns a client that uses assumed role credentials.
    * Otherwise, returns a client with default credentials.
    */
  def getClientForBucket(bucket: String): S3 = {
    externalBucketToRole.get(bucket) match {
      case Some(roleArn) => getClientForExternalBucket(bucket, roleArn)
      case None => getDefaultClientForBucket(bucket)
    }
  }

  /**
    * Get an S3 client with assumed role credentials for an external bucket.
    */
  private def getClientForExternalBucket(
    bucket: String,
    roleArn: String
  ): S3 = {
    val cacheKey = s"$bucket:$roleArn"
    externalBucketClientsMap.getOrElseUpdate(cacheKey, {
      val region = getRegionForBucket(bucket)
      val awsS3Client = createS3ClientWithAssumedRole(region, roleArn)
      new S3(awsS3Client)
    })
  }

  /**
    * Get an S3 client with default credentials for a bucket.
    */
  private def getDefaultClientForBucket(bucket: String): S3 = {
    val region = getRegionForBucket(bucket)

    s3ClientsMap.getOrElseUpdate(region, {
      val awsS3Client = createS3Client(region)
      new S3(awsS3Client)
    })
  }

  private def createS3Client(region: String): AmazonS3 = {
    createS3ClientWithCredentials(
      region,
      DefaultAWSCredentialsProviderChain.getInstance()
    )
  }

  private def createS3ClientWithAssumedRole(
    region: String,
    roleArn: String
  ): AmazonS3 = {
    val stsClient = AWSSecurityTokenServiceClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()

    val assumeRoleCredentialsProvider =
      new STSAssumeRoleSessionCredentialsProvider.Builder(
        roleArn,
        "pennsieve-api-external-bucket-session"
      ).withStsClient(stsClient)
        .build()

    createS3ClientWithCredentials(region, assumeRoleCredentialsProvider)
  }

  private def createS3ClientWithCredentials(
    region: String,
    credentialsProvider: AWSCredentialsProvider
  ): AmazonS3 = {
    val regionalConfig =
      new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(regionalConfig)
      .withCredentials(credentialsProvider)
      .withRegion(region)
      .build()
  }
}
