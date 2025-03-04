package com.pennsieve.aws.s3

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder}

import scala.collection.concurrent.TrieMap

object S3ClientFactory {

  private val s3ClientsMap: TrieMap[String, S3] = new TrieMap[String, S3]()

  private val regionMappings: Map[String, String] = Map(
    "use-1" -> "us-east-1",
    "afs-1" -> "af-south-1",
    "use-2" -> "us-east-2",
    "usw-1" -> "us-west-1",
    "usw-2" -> "us-west-2"
    // Add more regions
  )
  def getClientForBucket(bucket: String): S3 = {
    val region = regionMappings
      .collectFirst { case (suffix, region) if bucket.endsWith(suffix) => region }
      .getOrElse("us-east-1")

    s3ClientsMap.getOrElseUpdate(region, {
      val awsS3Client = createS3Client(region)
      new S3(awsS3Client)
    })
  }
  private def createS3Client(region: String): AmazonS3 = {
    val regionalConfig = new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withClientConfiguration(regionalConfig)
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()
  }
}