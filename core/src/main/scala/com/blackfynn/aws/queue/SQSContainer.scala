// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.queue

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }

import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.blackfynn.aws.LocalAWSCredentialsProviderV2
import com.blackfynn.core.utilities.RedisContainer
import com.blackfynn.utilities.Container
import net.ceedubs.ficus.Ficus._

import java.net.URI

trait SQSContainer { self: Container =>

  val sqs: SQS

  val sqs_host: String = config.as[String]("sqs.host")
  val sqs_region: Region = Region.of(config.as[String]("sqs.region"))
}

trait AWSSQSContainer extends SQSContainer { self: Container =>

  override lazy val sqs: SQS = new SQS(
    SqsAsyncClient
      .builder()
      .region(sqs_region)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )

}

trait LocalSQSContainer extends SQSContainer { self: Container =>

  override lazy val sqs: SQS = new SQS(
    SqsAsyncClient
      .builder()
      .region(sqs_region)
      .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
      .endpointOverride(new URI(sqs_host))
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )
}

trait SQSDeduplicationContainer extends RedisContainer { self: Container =>
  val ttl: Int = config.as[Int]("sqs.deduplication.ttl")
  val dbIndex: Int = config.as[Int]("sqs.deduplication.redisDBIndex")

  val deduplicate: SQSMessage => Boolean =
    SQSDeduplicator(redisClientPool, dbIndex, ttl)
}
