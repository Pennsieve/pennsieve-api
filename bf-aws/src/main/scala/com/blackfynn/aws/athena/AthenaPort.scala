// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.aws.athena

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.athena.{
  AmazonAthenaAsyncClient,
  AmazonAthenaAsyncClientBuilder
}

import com.pennsieve.aws.LocalAWSCredentialsProvider

trait AthenaPort {
  def athena: AthenaClient
}

class AmazonAthenaPort(region: Regions) extends AthenaPort {
  override lazy val athena: AthenaClient = new AmazonAthenaClient(
    AmazonAthenaAsyncClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(region)
      .build()
  )
}

class LocalAthenaPort(host: String, region: Regions) extends AthenaPort {
  override lazy val athena: AthenaClient = new AmazonAthenaClient(
    AmazonAthenaAsyncClientBuilder
      .standard()
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(host, region.getName)
      )
      .build()
  )
}
