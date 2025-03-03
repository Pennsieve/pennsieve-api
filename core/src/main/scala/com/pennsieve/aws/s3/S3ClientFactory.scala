package com.pennsieve.aws.s3

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder}

import scala.collection.concurrent.TrieMap

object S3ClientFactory {

  private val regionalClientsMap: TrieMap[String, AmazonS3] = new TrieMap[String, AmazonS3]()

  def getClientForRegion(region: String): AmazonS3 = {

    regionalClientsMap.getOrElseUpdate(region, createS3Client(region))
  }

  def getRegionFromBucket(bucket: String): String = {
    val regionMappings: Map[String, String] = Map(
      "afs-1" -> "af-south-1",
      "use-2" -> "us-east-2",
      "usw-1" -> "us-west-1",
      "usw-2" -> "us-west-2"
      // Add more regions
    )
    regionMappings
      .collectFirst {
        case (suffix, region) if bucket.endsWith(suffix) => region
      }
      .getOrElse("us-east-1")
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