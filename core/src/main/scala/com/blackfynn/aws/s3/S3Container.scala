// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.s3

import com.blackfynn.utilities.Container
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.blackfynn.aws.LocalAWSCredentialsProvider
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
