// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.queue

import java.util.UUID

import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.blackfynn.core.utilities.RedisContainer
import com.blackfynn.utilities.Container
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  FlatSpec,
  Matchers,
  Suite,
  SuiteMixin
}
import com.blackfynn.test._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import com.redis.RedisClientPool

class ConfigContainer(val config: Config) extends Container

class SQSDeduplicationSpec
    extends FlatSpec
    with SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with PersistantTestContainers
    with RedisDockerContainer
    with Matchers { self: Suite =>

  var deduplicationContainer
    : ConfigContainer with RedisContainer with SQSDeduplicationContainer = _

  def config: Config =
    redisContainer.config
      .withValue(
        "sqs.deduplication.redisDBIndex",
        ConfigValueFactory.fromAnyRef(4)
      )
      .withValue("sqs.deduplication.ttl", ConfigValueFactory.fromAnyRef(2))

  override def afterStart(): Unit = {
    super.afterStart()

    deduplicationContainer = new ConfigContainer(config) with RedisContainer
    with SQSDeduplicationContainer {
      lazy override val redisClientPool = RedisContainer.poolFromConfig(config)
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    deduplicationContainer.redisClientPool.close
  }

  "deduplicate" should "correctly prevent two messages from being processed at the same time" in {
    val message =
      SQSMessage.builder().messageId(UUID.randomUUID.toString).build()

    val locked = deduplicationContainer.deduplicate(message)
    val failed = deduplicationContainer.deduplicate(message)

    locked should equal(true)
    failed should equal(false)
  }

  "deduplicate" should "allow messages to be processed again after the TTL on the lock ends" in {
    val message =
      SQSMessage.builder().messageId(UUID.randomUUID.toString).build()

    val locked = deduplicationContainer.deduplicate(message)

    // Wait for one second longer than the deduplication TTL
    Thread.sleep((deduplicationContainer.ttl * 1000) + 1000)

    val succeeded = deduplicationContainer.deduplicate(message)

    locked should equal(true)
    succeeded should equal(true)
  }

}
