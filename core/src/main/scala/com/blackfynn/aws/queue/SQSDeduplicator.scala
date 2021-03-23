/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
