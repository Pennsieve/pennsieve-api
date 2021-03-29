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

package com.pennsieve.migrations

import org.flywaydb.core.Flyway
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

object Main extends App {
  val config: Config = ConfigFactory.load()

  val migrationType: String = config.as[String]("migration.type")
  val organizationSchemaCount: Int = config
    .as[Int]("migration.organization_schema.count")
  val baseline: Boolean = config.as[Boolean]("migration.baseline")

  val pennsieveDBUser: String = config.as[String]("postgres.user")
  val pennsieveDBPassword: String = config.as[String]("postgres.password")
  val pennsieveDBHost: String = config.as[String]("postgres.host")
  val pennsieveDBPort: String = config.as[String]("postgres.port")
  val pennsieveDBDatabase: String = config.as[String]("postgres.database")
  val pennsieveDBUseSSL: Boolean = config.as[Boolean]("postgres.use_ssl")
  val pennsieveDBBaseUrl: String =
    s"jdbc:postgresql://${pennsieveDBHost}:${pennsieveDBPort}/${pennsieveDBDatabase}"
  val pennsieveDBUrl = {
    if (pennsieveDBUseSSL) pennsieveDBBaseUrl + "?ssl=true&sslmode=verify-ca"
    else pennsieveDBBaseUrl
  }

  val runner = new DatabaseMigrationRunner(
    pennsieveDBUrl,
    pennsieveDBUser,
    pennsieveDBPassword
  )

  migrationType.toLowerCase match {
    case "core" =>
      runner.migrateCoreSchema(baseline)
    case "organization" =>
      runner.migrateOrganizationSchemas(organizationSchemaCount)
    case "all" => {
      runner.migrateCoreSchema(baseline)
      runner.migrateOrganizationSchemas(organizationSchemaCount)
    }
    case _ =>
      throw new Exception("expected one of 'core', 'organization', 'all'")
  }
}

/**
  * Note that we use an old version of Flyway, docs here:
  * https://www.javadoc.io/doc/org.flywaydb/flyway-core/4.2.0/org/flywaydb/core/Flyway.html
  *
  * TODO: pass data source instead?
  */
class DatabaseMigrationRunner(
  pennsieveDBUrl: String,
  pennsieveDBUser: String,
  pennsieveDBPassword: String
) {

  /**
    * Creating a new Flyway instance for each migration, particularly when
    * iterating through organization schemas, is much faster and produces lower
    * DB load than sharing the same instance.
    */
  private def createFlyway() = {
    val flyway: Flyway = new Flyway
    flyway.setDataSource(pennsieveDBUrl, pennsieveDBUser, pennsieveDBPassword)
    flyway
  }

  def migrateCoreSchema(baseline: Boolean = false) = {
    println("Migrating core Pennsieve schema")

    val flyway = createFlyway()
    flyway.setLocations("classpath:db/migrations")
    flyway.setSchemas("pennsieve")
    flyway.setBaselineOnMigrate(baseline)
    flyway.migrate

    println("")
  }

  def migrateOrganizationSchema(schemaId: Int) = {
    println(s"Migrating organization schema $schemaId ")

    val flyway = createFlyway()
    flyway.setLocations("classpath:db/organization-schema-migrations")
    flyway.setSchemas(schemaId.toString)
    flyway.migrate

    println("")
  }

  def migrateOrganizationSchemas(schemaCount: Int) = {
    for (i <- 1 to schemaCount) {
      migrateOrganizationSchema(i)
    }

    refreshUnionView
  }

  def refreshUnionView = {
    println("Refreshing union view")
    createFlyway()
      .getDataSource()
      .getConnection()
      .createStatement()
      .execute("SELECT pennsieve.refresh_union_view('files')")
  }
}
