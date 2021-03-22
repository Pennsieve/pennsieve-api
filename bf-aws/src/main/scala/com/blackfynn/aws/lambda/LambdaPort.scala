// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.lambda

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.{
  AWSLambdaAsync,
  AWSLambdaAsyncClientBuilder
}

import com.blackfynn.aws.LocalAWSCredentialsProvider

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
