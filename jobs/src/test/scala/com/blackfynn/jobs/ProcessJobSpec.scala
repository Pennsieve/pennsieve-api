package com.pennsieve.jobs

import com.pennsieve.messages._
import com.pennsieve.test.helpers.EitherValue._
import io.circe.syntax._
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.pennsieve.audit.middleware.TraceId
import org.scalatest.{ FlatSpec, Matchers }

class ProcessJobSpec extends FlatSpec with Matchers {

  "Processor.parse" should "be able to parse a CachePopulationJob" in {
    val job: BackgroundJob = CachePopulationJob(false, Some(123))
    val message = SQSMessage.builder().body(job.asJson.noSpaces).build()

    assert(Processor.parse(message).value._2 == job)
  }

  it should "be able to parse a DeletePackageJob" in {
    val job: BackgroundJob = DeletePackageJob(
      packageId = 2,
      organizationId = 12,
      userId = "321",
      traceId = TraceId("1234")
    )
    val message = SQSMessage.builder().body(job.asJson.noSpaces).build()

    assert(Processor.parse(message).value._2 == job)
  }

  it should "be able to parse a DeleteDatasetJob" in {
    val job: BackgroundJob = DeleteDatasetJob(
      datasetId = 2,
      organizationId = 12,
      userId = "321",
      traceId = TraceId("1234")
    )
    val message = SQSMessage.builder().body(job.asJson.noSpaces).build()

    assert(Processor.parse(message).value._2 == job)
  }
}
