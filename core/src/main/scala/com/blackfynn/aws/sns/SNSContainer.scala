package com.blackfynn.aws.sns

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient

import com.blackfynn.aws.LocalAWSCredentialsProviderV2
import com.blackfynn.utilities.Container
import net.ceedubs.ficus.Ficus._
import java.net.URI

trait SNSContainer { self: Container =>

  val snsHost: String = config.getAs[String]("sns.host").getOrElse("")
  val snsRegion: Region = config.getAs[String]("sns.region") match {
    case Some(region) => Region.of(region)
    case None => Region.US_EAST_1
  }

  val snsClient: SnsAsyncClient
}

trait AWSSNSContainer extends SNSContainer { self: Container =>

  override val snsClient: SnsAsyncClient = SnsAsyncClient
    .builder()
    .region(snsRegion)
    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
    .build()
}

trait LocalSNSContainer extends SNSContainer { self: Container =>

  override val snsClient: SnsAsyncClient = SnsAsyncClient
    .builder()
    .region(snsRegion)
    .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
    .endpointOverride(new URI(snsHost))
    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
    .build()
}
