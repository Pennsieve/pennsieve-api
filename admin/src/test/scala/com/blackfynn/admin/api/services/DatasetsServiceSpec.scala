package com.blackfynn.admin.api.services
import akka.http.scaladsl.model.HttpMethods.{ DELETE, GET, POST }
import com.blackfynn.models.{ PublishStatus }
import io.circe.syntax._
import akka.http.scaladsl.model.StatusCodes.{ NoContent, OK }
import com.blackfynn.discover.client.definitions.{
  DatasetPublishStatus,
  SponsorshipRequest,
  SponsorshipResponse
}

class DatasetsServiceSpec extends AdminServiceSpec {

  "datasets service" should {
    "return all published datasets in an organization" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.id}/datasets",
        session = adminSession
      ) ~>
        routes ~> check {

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[DatasetPublishStatus]] should contain theSameElementsAs (List(
          DatasetPublishStatus(
            "PPMI",
            organizationOne.id,
            1,
            None,
            0,
            PublishStatus.PublishInProgress,
            None,
            Some(SponsorshipRequest(Some("foo"), Some("bar"), Some("baz")))
          ),
          DatasetPublishStatus(
            "TUSZ",
            organizationOne.id,
            2,
            None,
            0,
            PublishStatus.PublishInProgress,
            None
          )
        ))
      }

    }

    "add a sponsorship to a dataset" in {
      testRequest(
        POST,
        s"/organizations/${organizationOne.id}/datasets/1/sponsor",
        json = Some(SponsorshipRequest(Some("foo")).asJson),
        session = adminSession
      ) ~>
        routes ~> check {

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[SponsorshipResponse] shouldEqual SponsorshipResponse(1, 1)
      }

    }

    "remove a sponsorship from a dataset" in {
      testRequest(
        DELETE,
        s"/organizations/${organizationOne.id}/datasets/1/sponsor",
        session = adminSession
      ) ~>
        routes ~> check {

        status shouldEqual NoContent
      }

    }
  }

}
