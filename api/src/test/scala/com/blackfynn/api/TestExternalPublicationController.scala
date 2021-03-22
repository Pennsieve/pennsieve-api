// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.api

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import cats.data._
import cats.implicits._
import com.blackfynn.dtos._
import com.blackfynn.helpers._
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import org.json4s._
import org.json4s.jackson.Serialization.{ read, write }
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

import java.net.URLEncoder
import scala.concurrent.Future

class TestExternalPublicationController
    extends BaseApiTest
    with DataSetTestMixin {

  override def afterStart(): Unit = {
    super.afterStart()

    val httpClient: HttpRequest => Future[HttpResponse] = { _ =>
      Future.successful(HttpResponse())
    }

    addFilter(
      new ExternalPublicationController(
        insecureContainer,
        secureContainerBuilder,
        new MockDoiClient(httpClient, ec, materializer),
        system.dispatcher
      ),
      "/*"
    )
  }

  val ValidDoi = Doi("10.21397/jili-ef5r") // MockDoiClient is aware of this value
  val InvalidDoi = Doi("10.21397/adbb-6903")

  def encode(q: String): String =
    URLEncoder.encode(q, "utf-8")

  def createExternalPublication(
    dataset: Dataset,
    doi: Doi
  ): ExternalPublicationDTO =
    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=$doi&relationshipType=IsReferencedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[ExternalPublicationDTO]
    }

  test("link external publications with valid DOIs to a dataset") {

    val dataset = createDataSet("dataset")

    Seq(
      ValidDoi.value,
      "10.1093/brain/aww045",
      "10.1002/(sici)1099-1409(199908/10)3:6/7<672::aid-jpp192>3.0.co;2-8"
    ).foreach { doi =>
      put(
        s"/datasets/${dataset.nodeId}/external-publications?doi=${encode(doi)}&relationshipType=IsDerivedFrom",
        headers = authorizationHeader(loggedInJwt)
      ) {
        status should equal(200)
        parsedBody.extract[ExternalPublicationDTO].doi.value shouldBe doi
        parsedBody
          .extract[ExternalPublicationDTO]
          .relationshipType
          .value shouldBe RelationshipType.IsDerivedFrom

      }
    }
  }

  test("require a DOI query parameter to link external publications") {

    val dataset = createDataSet("dataset")

    put(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsSourceOf",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(400)
    }
  }

  test("reject malformed DOIs") {
    val dataset = createDataSet("dataset")

    Seq(
      "10.134", // missing suffix
      "10.134/", // missing suffix
      "10.abc/1234" // letters in prefix
    ).foreach(
      doi =>
        put(
          s"/datasets/${dataset.nodeId}/external-publications?doi=${encode(doi)}&relationshipType=IsSourceOf",
          headers = authorizationHeader(loggedInJwt)
        ) {
          status should equal(400)
        }
    )
  }

  test("reject incorrect relationshipType") {
    val dataset = createDataSet("dataset")

    Seq("AnIncorrectRelationshipType", "asdfghjkl;").foreach(
      relationshipType =>
        put(
          s"/datasets/${dataset.nodeId}/external-publications?doi=10.21397/jili-ef5d&relationshipType=$relationshipType",
          headers = authorizationHeader(loggedInJwt)
        ) {
          status should equal(400)
        }
    )
  }

  test("lowercase DOI when linking to dataset") {

    val dataset = createDataSet("dataset")
    val uppercaseDoi = Doi("10.21397/JILI-EF5T")

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=$uppercaseDoi&relationshipType=IsSourceOf",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[ExternalPublicationDTO].doi shouldBe Doi(
        "10.21397/jili-ef5t"
      )
    }
  }

  test("upsert duplicate external publications") {
    val dataset = createDataSet("dataset")

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=$ValidDoi&relationshipType=IsDescribedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    put(
      s"/datasets/${dataset.nodeId}/external-publications?doi=$ValidDoi&relationshipType=IsDescribedBy",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
    }

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[ExternalPublicationDTO]].length shouldBe 1
    }
  }

  test("get external publications for a dataset") {

    val dataset = createDataSet("dataset")

    val externalPublication1 =
      createExternalPublication(dataset, ValidDoi)
    val externalPublication2 =
      createExternalPublication(dataset, InvalidDoi)

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody
        .extract[List[ExternalPublicationDTO]]
        .map(p => (p.doi, p.notFound, p.citation)) shouldBe List(
        (ValidDoi, false, Some("A citation")),
        (InvalidDoi, true, None)
      )
    }
  }

  test("get a citation for external publications for a dataset") {
    get(
      s"/datasets/external-publications/citation?doi=${ValidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[CitationResult].citation shouldBe ("A citation")
    }

    get(
      s"/datasets/external-publications/citation?doi=${InvalidDoi.value}",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
    }
  }

  test("remove external publication from a dataset") {
    val dataset = createDataSet("dataset")

    createExternalPublication(dataset, ValidDoi)

    delete(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsReferencedBy&doi=$ValidDoi",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(204)
    }

    get(
      s"/datasets/${dataset.nodeId}/external-publications",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(200)
      parsedBody.extract[List[ExternalPublicationDTO]] shouldBe empty
    }
  }

  test("return error when external publication does not exist") {
    val dataset = createDataSet("dataset")

    delete(
      s"/datasets/${dataset.nodeId}/external-publications?relationshipType=IsReferencedBy&doi=$ValidDoi",
      headers = authorizationHeader(loggedInJwt)
    ) {
      status should equal(404)
    }
  }
}
