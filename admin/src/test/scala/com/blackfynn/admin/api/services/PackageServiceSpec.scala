// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.admin.api.services

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes.OK
import akka.testkit.TestKitBase
import com.pennsieve.admin.api.Router.{
  AdminETLServiceContainer,
  InsecureResourceContainer,
  SecureResourceContainer
}
import com.pennsieve.aws.s3.LocalS3Container
import com.pennsieve.admin.api.dtos.{
  JobDTO,
  SimpleDatasetDTO,
  SimpleOrganizationDTO,
  UserDTO
}
import com.pennsieve.admin.api.{ AdminContainer, Router }
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.clients._
import com.pennsieve.core.utilities._
import com.pennsieve.dtos.{ Builders, PackageDTO }
import com.pennsieve.models._
import io.circe.syntax._

class PackageServiceSpec extends AdminServiceSpec with TestKitBase {

  "package service" should {

    "return the requested package to an admin user" in {

      val container = secureContainerBuilder(admin, organizationOne)

      val ds = container.datasetManager
        .create("Test Dataset")
        .await
        .right
        .get

      val importId = java.util.UUID.randomUUID()

      val packageOne = container.packageManager
        .create(
          "Test Package",
          PackageType.Image,
          PackageState.READY,
          ds,
          Some(admin.id),
          None,
          Some(importId)
        )
        .await
        .right
        .get

      val expected =
        PackageResponse(
          PackageDTO.simple(packageOne, ds),
          SimpleOrganizationDTO(organizationOne),
          UserDTO(admin),
          SimpleDatasetDTO(ds),
          JobDTO(
            Some(importId),
            s"s3://pennsieve-dev-storage-use1/${admin.email}/data/${importId.toString}",
            s"https://elk.pennsieve.io/app/kibana#/discover?_g=(refreshInterval:(pause:!t,value:0),time:(from:now-1y,mode:quick,to:now))&_a=(columns:!(pennsieve.tier,message),filters:!(('$$state':(store:appState),meta:(alias:!n,disabled:!f,index:'982ea520-5eb7-11e8-9747-6b75a1731072',key:pennsieve.import_id,negate:!f,params:(query:'${importId.toString}',type:phrase),type:phrase,value:'${importId.toString}'),query:(match:(pennsieve.import_id:(query:'${importId.toString}',type:phrase))))),index:'982ea520-5eb7-11e8-9747-6b75a1731072',interval:auto,query:(language:lucene,query:''),sort:!('@timestamp',desc))"
          )
        )

      testRequest(
        GET,
        s"/packages/${packageOne.nodeId}",
        session = adminSession
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[PackageResponse] should be(expected)
      }
    }

  }
}
