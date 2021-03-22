package com.blackfynn.jobs.types

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import com.blackfynn.core.utilities.{ DatabaseContainer, InsecureContainer }
import com.blackfynn.managers.ManagerSpec
import com.blackfynn.messages.BackgroundJob._
import com.blackfynn.messages.{
  BackgroundJob,
  DatasetChangelogEventJob,
  EventInstance
}
import com.blackfynn.models.{
  ChangelogEventCursor,
  ChangelogEventDetail,
  ChangelogEventName
}
import com.typesafe.config.ConfigFactory
import io.circe.parser._
import org.scalatest.{ BeforeAndAfterAll, FlatSpec, Matchers }

import java.util.UUID
import scala.concurrent.ExecutionContext

class DatasetChangelogEventJobSpec
    extends FlatSpec
    with SpecHelper
    with Matchers
    with TestKitBase
    with BeforeAndAfterAll
    with ManagerSpec {

  implicit lazy val system: ActorSystem = ActorSystem(
    "DatasetChangelogEventJobSpec"
  )
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()
  implicit lazy val ec: ExecutionContext = system.dispatcher

  var insecureContainer: DatabaseContainer = _

  override def afterStart(): Unit = {
    val config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withFallback(redisContainer.config)

    insecureContainer = new InsecureContainer(config) with DatabaseContainer {
      override val postgresUseSSL = false
    }

    super.afterStart()
  }

  override def afterAll(): Unit = {
    insecureContainer.db.close()
    super.afterAll()
  }

  "multiple DatasetChangelogEventJob payload versions" should "be supported" in {

    val messageV1 = s"""
    |{
    |  "DatasetChangelogEventJob": {
    |    "organizationId": ${testOrganization.id},
    |    "datasetId": ${testDataset.id},
    |    "userId": "${superAdmin.nodeId}",
    |    "eventType": "CREATE_PACKAGE",
    |    "eventDetail": { "id": 123 },
    |    "traceId": "1234-5678",
    |    "id": "${UUID.randomUUID().toString}"
    |  }
    |}
    |""".stripMargin
    val p1 = decode[BackgroundJob](messageV1)
    p1.isRight shouldBe (true)
    val job1 = p1.right.get.asInstanceOf[DatasetChangelogEventJob]
    job1.events shouldBe (None)
    job1.eventType.isDefined shouldBe (true)
    job1.eventDetail.isDefined shouldBe (true)
    job1.listEvents().length shouldBe (1)

    val messageV2 = s"""
       |{
       |  "DatasetChangelogEventJob": {
       |    "organizationId": ${testOrganization.id},
       |    "datasetId": ${testDataset.id},
       |    "userId": "${superAdmin.nodeId}",
       |    "events": [
       |      {"eventType": "CREATE_PACKAGE", "eventDetail": { "id": 123 }, "timestamp": "2007-12-03T10:15:30+01:00[Europe/Paris]" },
       |      {"eventType": "DELETE_PACKAGE", "eventDetail": { "id": 123 }}
       |    ],
       |    "traceId": "1234-5678",
       |    "id": "${UUID.randomUUID().toString}"
       |  }
       |}
       |""".stripMargin
    val p2 = decode[BackgroundJob](messageV2)
    p2.isRight shouldBe (true)
    val job2 = p2.right.get.asInstanceOf[DatasetChangelogEventJob]
    job2.events.get.length shouldBe (2)
    job2.eventType shouldBe (None)
    job2.eventDetail shouldBe (None)
    job2.listEvents().length shouldBe (2)
  }

  "running the dataset changelog event job" should "add an event to the changelog of events" in {
    val u = createUser()
    val ds = createDataset(organization = testOrganization, user = u)
    val message = s"""
      |{
      |  "DatasetChangelogEventJob": {
      |    "organizationId": ${testOrganization.id},
      |    "datasetId": ${ds.id},
      |    "userId": "${u.nodeId}",
      |    "events": [
      |      {"eventType": "CREATE_PACKAGE", "eventDetail": { "id": 123, "timestamp": "2019-03-27T10:15:30+05:30" }},
      |      {"eventType": "DELETE_PACKAGE", "eventDetail": { "id": 123 }}
      |    ],
      |    "traceId": "1234-5678",
      |    "id": "${UUID.randomUUID().toString}"
      |  }
       }
      |""".stripMargin
    val m = decode[BackgroundJob](message)
    val dcle: DatasetChangelogEventJob =
      m.right.get.asInstanceOf[DatasetChangelogEventJob]

    val datasetChangelogEventRunner =
      new DatasetChangelogEvent(insecureContainer)
    datasetChangelogEventRunner.run(dcle).await.isRight shouldBe (true)

    // CREATE
    val clm = changelogManager(organization = testOrganization, user = u)
    val (createEvents, _) = clm
      .getEvents(
        dataset = ds,
        limit = 10,
        cursor = ChangelogEventCursor(
          ChangelogEventName.CREATE_PACKAGE,
          None,
          None,
          None
        )
      )
      .await
      .right
      .get
    createEvents.length shouldBe (1)
    createEvents.head.eventType shouldBe (ChangelogEventName.CREATE_PACKAGE)
    createEvents.head.detail shouldBe (ChangelogEventDetail
      .CreatePackage(id = 123, None, None, None))

    // DELETE
    val (deleteEvents, _) = clm
      .getEvents(
        dataset = ds,
        limit = 10,
        cursor = ChangelogEventCursor(
          ChangelogEventName.DELETE_PACKAGE,
          None,
          None,
          None
        )
      )
      .await
      .right
      .get
    deleteEvents.length shouldBe (1)
    deleteEvents.head.eventType shouldBe (ChangelogEventName.DELETE_PACKAGE)
    deleteEvents.head.detail shouldBe (ChangelogEventDetail
      .DeletePackage(id = 123, None, None, None))
  }

  "parsing a changelog event job" should "fail if the event is not well-formed or missing data" in {
    // "eventDetail" is missing the required "id" property:
    val message = s"""
       |{
       |  "DatasetChangelogEventJob": {
       |    "organizationId": ${testOrganization.id},
       |    "datasetId": ${testDataset.id},
       |    "userId": "${superAdmin.nodeId}",
       |    "events": [{"eventType": "CREATE_PACKAGE", "eventDetail": { }}],
       |    "traceId": "1234-5678",
       |    "id": "${UUID.randomUUID().toString}"
       |  }
       }
       |""".stripMargin
    val m = decode[BackgroundJob](message)
    val dcle: DatasetChangelogEventJob =
      m.right.get.asInstanceOf[DatasetChangelogEventJob]
    val datasetChangelogEventRunner =
      new DatasetChangelogEvent(insecureContainer)
    val result = datasetChangelogEventRunner.run(dcle).await
    result.isLeft shouldBe (true)
  }
}
