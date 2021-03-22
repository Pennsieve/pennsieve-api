package com.blackfynn.test.helpers

import com.blackfynn.core.utilities._
import com.blackfynn.test._
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  Suite,
  SuiteMixin
}
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

trait CoreSpecHarness[
  Container <: SessionManagerContainer with RedisContainer with OrganizationManagerContainer with UserManagerContainer
] extends SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with CoreSeed[Container]
    with TestDatabase
    with PersistantTestContainers
    with PostgresDockerContainer
    with RedisDockerContainer { self: Suite =>

  var config: Config = _
  var testDIContainer: Container = _

  def createTestDIContainer: Container

  override def afterStart(): Unit = {
    super.afterStart()
    config = ConfigFactory
      .empty()
      .withFallback(redisContainer.config)
      .withFallback(postgresContainer.config)
      .withValue("email.host", ConfigValueFactory.fromAnyRef("test"))
      .withValue(
        "email.trials_host",
        ConfigValueFactory.fromAnyRef("trials_test")
      )
      .withValue(
        "email.support_email",
        ConfigValueFactory.fromAnyRef("support@pennsieve.org")
      )
      .withValue("session_timeout", ConfigValueFactory.fromAnyRef(28800))
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
    testDIContainer = createTestDIContainer
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    if (testDIContainer == null) {
      throw new RuntimeException(
        s"testDIContainer property of ${this.getClass.getName} is null. Aborting tests."
      )
    }
    testDIContainer.redisClientPool.withClient { c =>
      c.flushall
    }

    testDIContainer.db.run(clearDB).await

    seed(testDIContainer)
  }

  override def afterEach(): Unit = {
    testDIContainer.db.run(clearSchemas).await
    super.afterEach()
  }

  override def afterAll(): Unit = {
    if (testDIContainer != null) {
      testDIContainer.db.close()
      testDIContainer.redisClientPool.close
    }
    super.afterAll()
  }
}
