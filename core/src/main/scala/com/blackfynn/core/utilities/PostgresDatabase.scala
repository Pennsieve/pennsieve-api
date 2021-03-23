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

package com.pennsieve.core.utilities

import slick.util.AsyncExecutor
import com.pennsieve.traits.PostgresProfile.api.Database

import com.zaxxer.hikari.HikariDataSource

case class PostgresDatabase(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  useSSL: Boolean = true
) {
  val jdbcBaseURL: String = s"jdbc:postgresql://$host:$port/$database"
  val jdbcURL = {
    if (useSSL) jdbcBaseURL + "?ssl=true&sslmode=verify-ca"
    else jdbcBaseURL
  }

  lazy val forURL: Database = {
    val maxConnections: Int = 10 // Tune this
    val leakDetectionThreshold = 60 * 1000 // 1 minute

    val ds = new HikariDataSource()
    ds.setJdbcUrl(jdbcURL)
    ds.setUsername(user)
    ds.setPassword(password)
    ds.setMaximumPoolSize(maxConnections)
    ds.setDriverClassName("org.postgresql.Driver")
    ds.setLeakDetectionThreshold(leakDetectionThreshold)

    Database.forDataSource(
      ds,
      maxConnections = None, // Ignored if an executor is provided
      executor = AsyncExecutor(
        name = "AsyncExecutor.pennsieve",
        minThreads = maxConnections,
        maxThreads = maxConnections,
        maxConnections = maxConnections,
        queueSize = 1000
      )
    )
  }
}
