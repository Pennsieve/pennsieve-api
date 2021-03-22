// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.db.{ OrganizationsMapper, PackagesMapper }
import com.blackfynn.etl.`data-cli`.container._
import com.blackfynn.etl.`data-cli`.exceptions._
import com.blackfynn.traits.PostgresProfile.api._
import cats.implicits._
import com.blackfynn.models.PackageType

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scopt.OptionParser

object UpdatePackageType {

  def update(
    packageId: Int,
    organizationId: Int,
    packageType: PackageType
  )(implicit
    container: Container
  ): Future[Unit] = {

    val query: DBIOAction[Unit, NoStream, Effect.All with Effect.Write] = for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      packages = new PackagesMapper(organization)
      _ <- packages.updateType(packageId, packageType)
    } yield ()

    container.db.run(query.transactionally)

  }

  // default values required by scopt
  case class CLIConfig(
    packageType: String = "Image",
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
     | package-id = $packageId
     | organization-id = $organizationId
     | package-type = $packageType
  """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("update-package-type")

    opt[String]("package-type")
      .required()
      .valueName("<type>")
      .action((value, config) => config.copy(packageType = value))
      .text("package-type is a required file property")

    opt[Int]("package-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(packageId = value))
      .text("package-id is a required file property")

    opt[Int]("organization-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(organizationId = value))
      .text("organization-id is a required file property")
  }

  def decodePackageType(packageType: String): Future[PackageType] = {
    PackageType.values.find(_.toString.toUpperCase == packageType.toUpperCase) match {
      case None =>
        Future.failed(new Exception(s"invalid package-type $packageType"))
      case Some(pkgType) => Future.successful(pkgType)
    }
  }

  def parse(args: Array[String]): Future[CLIConfig] =
    parser.parse(args, new CLIConfig()) match {
      case None => Future.failed(new ScoptParsingFailure)
      case Some(config) => Future.successful(config)
    }

  def run(args: Array[String], getContainer: Future[Container]): Future[Unit] =
    for {
      config <- parse(args)
      packageType <- decodePackageType(config.packageType)
      container <- getContainer
      _ <- update(config.packageId, config.organizationId, packageType)(
        container
      )
    } yield ()
}
