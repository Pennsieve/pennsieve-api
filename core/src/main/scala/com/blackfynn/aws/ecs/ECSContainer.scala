// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.ecs

import com.blackfynn.utilities.Container
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.ecs.{
  AmazonECSAsync,
  AmazonECSAsyncClientBuilder
}
import com.blackfynn.aws.LocalAWSCredentialsProvider
import net.ceedubs.ficus.Ficus._

trait ECSContainer { self: Container =>

  val ecs_host: String = config.as[String]("ecs.host")
  val ecs_region: Regions = config.as[Option[String]]("ecs.region") match {
    case Some(region) => Regions.fromName(region)
    case None => Regions.US_EAST_1
  }

  val ecs: ECSTrait

}

trait AWSECSContainer extends ECSContainer { self: Container =>

  private lazy val ecsClient: AmazonECSAsync =
    AmazonECSAsyncClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(ecs_region)
      .build()

  override lazy val ecs: AWSECS = new AWSECS(ecsClient)

}

trait LocalECSContainer extends ECSContainer { self: Container =>

  private lazy val ecsClient: AmazonECSAsync =
    AmazonECSAsyncClientBuilder
      .standard()
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(ecs_host, ecs_region.getName())
      )
      .build()

  override lazy val ecs: AWSECS = new AWSECS(ecsClient)

}
