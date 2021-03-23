// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.aws.queue

import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.redis.{ RedisClientPool, Seconds }
import com.redis.api.StringApi.NX

import scala.concurrent.duration._

object SQSDeduplicator
    extends Function3[RedisClientPool, Int, Int, SQSMessage => Boolean] {

  def apply(
    pool: RedisClientPool,
    dbIndex: Int,
    ttl: Int
  ): SQSMessage => Boolean = {

    def deduplicate(message: SQSMessage): Boolean = {
      pool.withClient { client =>
        client.select(dbIndex)
        // performs the Redis command: SET messageId "" nx ex ttl
        // which is an atomic set-if-not-exists operation  with a ttl (in seconds)
        client.set(message.messageId, "", NX, ttl.seconds)
      }
    }

    deduplicate
  }
}
