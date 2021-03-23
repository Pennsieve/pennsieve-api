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

package com.pennsieve.managers

import com.redis.RedisClientPool

class RedisManager(pool: RedisClientPool, database: Int) {

  def get(id: String): Option[String] =
    pool.withClient { client =>
      {
        client.select(0)
        client.get(id)
      }
    }

  def del(id: String): Boolean =
    pool.withClient { client =>
      {
        client.select(0)
        client
          .del(id)
          .contains(1)
      }
    }

  def hget(hashName: String, fieldName: String): Option[String] = {
    pool.withClient { client =>
      {
        client.select(0)
        client.hget(hashName, fieldName)
      }
    }
  }

  def hdel(hashName: String, fieldName: String): Option[Long] = {
    pool.withClient { client =>
      {
        client.select(0)
        client.hdel(hashName, fieldName)
      }
    }
  }

  def hinc(hashName: String, fieldName: String): Unit = {
    pool.withClient { client =>
      {
        client.select(0)
        client.hincrby(hashName, fieldName, 1)
      }
    }
  }

  def set(key: String, value: String, ttl: Int): Boolean =
    pool.withClient { client =>
      {
        client.select(0)
        client.set(key, value)
        client.expire(key, ttl)
      }
    }

  def set(key: String, value: String): Boolean =
    pool.withClient { client =>
      {
        client.select(0)
        client.set(key, value)
      }
    }

  def expire(key: String, ttl: Int): Boolean =
    pool.withClient { client =>
      {
        client.select(0)
        client.expire(key, ttl)
      }
    }

}
