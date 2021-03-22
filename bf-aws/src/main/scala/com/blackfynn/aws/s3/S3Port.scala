// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.s3

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.blackfynn.aws.LocalAWSCredentialsProvider

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
