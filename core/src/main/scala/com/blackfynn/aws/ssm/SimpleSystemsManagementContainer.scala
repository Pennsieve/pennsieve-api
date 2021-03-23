// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.aws.ssm

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementAsyncClientBuilder

import com.pennsieve.aws.LocalAWSCredentialsProvider
import com.pennsieve.utilities.Container

import net.ceedubs.ficus.Ficus._

trait SimpleSystemsManagementContainer { self: Container =>

  def ssm: SimpleSystemsManagementTrait

  val ssm_host: String = config.as[String]("ssm.host")
  val ssm_region: Regions = Regions.fromName(config.as[String]("ssm.region"))

}

trait AWSSimpleSystemsManagementContainer
    extends SimpleSystemsManagementContainer { self: Container =>

  override lazy val ssm: AWSSimpleSystemsManagement =
    new AWSSimpleSystemsManagement(
      AWSSimpleSystemsManagementAsyncClientBuilder
        .standard()
        .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
        .withRegion(ssm_region)
        .build()
    )

}

trait LocalSimpleSystemsManagerContainer
    extends SimpleSystemsManagementContainer { self: Container =>

  override lazy val ssm: AWSSimpleSystemsManagement =
    new AWSSimpleSystemsManagement(
      AWSSimpleSystemsManagementAsyncClientBuilder
        .standard()
        .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
        .withEndpointConfiguration(
          new EndpointConfiguration(ssm_host, ssm_region.getName())
        )
        .build()
    )

}
