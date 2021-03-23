// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import com.pennsieve.db.{ DatasetsMapper, DimensionsMapper }
import com.pennsieve.models.{
  DBPermission,
  Dimension,
  DimensionAssignment,
  DimensionProperties,
  Organization,
  Package
}
import com.pennsieve.core.utilities.FutureEitherHelpers
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  NotFound,
  UnsupportedPackageType
}
import com.pennsieve.models.Imaging

import scala.concurrent.{ ExecutionContext, Future }
import com.pennsieve.traits.PostgresProfile.api._

class DimensionManager(db: Database, organization: Organization) {

  val table = new DimensionsMapper(organization)

  def get(
    id: Int,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dimension] = {
    FutureEitherHelpers
      .assert[CoreError](`package`.`type`.isInstanceOf[Imaging])(
        UnsupportedPackageType(`package`.`type`)
      )
      .flatMap { _ =>
        db.run(
            table
              .filter(_.id === id)
              .filter(_.packageId === `package`.id)
              .result
              .headOption
          )
          .whenNone[CoreError](
            NotFound(s"dimension ($id) in package ${`package`.id}")
          )
      }
  }

  def get(
    ids: Set[Int],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Dimension]] = {
    FutureEitherHelpers
      .assert[CoreError](`package`.`type`.isInstanceOf[Imaging])(
        UnsupportedPackageType(`package`.`type`)
      )
      .flatMap { _ =>
        db.run(
            table
              .filter(_.id inSet ids)
              .filter(_.packageId === `package`.id)
              .result
          )
          .map(_.toList)
          .toEitherT
      }
  }

  def getAll(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Dimension]] = {
    FutureEitherHelpers
      .assert[CoreError](`package`.`type`.isInstanceOf[Imaging])(
        UnsupportedPackageType(`package`.`type`)
      )
      .flatMap { _ =>
        db.run(
            table
              .getByPackageId(`package`.id)
              .result
          )
          .map(_.toList)
          .toEitherT
      }
  }

  def create(
    properties: DimensionProperties,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dimension] = {

    lazy val query = table returning table += new Dimension(
      `package`.id,
      properties.name.trim,
      properties.length,
      properties.resolution,
      properties.unit,
      properties.assignment
    )

    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      dimension <- db.run(query.transactionally).toEitherT
    } yield dimension
  }

  def create(
    batch: List[DimensionProperties],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Dimension]] = {

    lazy val query = DBIO.sequence(
      batch.map(
        properties =>
          table returning table += new Dimension(
            `package`.id,
            properties.name.trim,
            properties.length,
            properties.resolution,
            properties.unit,
            properties.assignment
          )
      )
    )

    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      dimensions <- db.run(query.transactionally).toEitherT
    } yield dimensions
  }

  def update(
    dimension: Dimension,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Dimension] = {
    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      _ <- db
        .run(
          table
            .filter(_.id === dimension.id)
            .update(dimension)
        )
        .toEitherT
    } yield dimension
  }

  def update(
    dimensions: List[Dimension],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Dimension]] = {
    lazy val query = DBIO.sequence(
      dimensions.map(
        dimension =>
          table
            .filter(_.id === dimension.id)
            .update(dimension)
      )
    )

    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      _ <- db.run(query.transactionally).toEitherT
    } yield dimensions
  }

  def delete(
    dimension: Dimension,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      result <- db
        .run(
          table
            .filter(_.id === dimension.id)
            .filter(_.packageId === `package`.id)
            .delete
        )
        .toEitherT
    } yield result
  }

  def delete(
    dimensions: List[Dimension],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Int]] = {
    lazy val query = DBIO.sequence(
      dimensions.map(
        dimension =>
          table
            .filter(_.id === dimension.id)
            .filter(_.packageId === `package`.id)
            .delete
      )
    )

    for {
      _ <- FutureEitherHelpers.assert[CoreError](
        `package`.`type`.isInstanceOf[Imaging]
      )(UnsupportedPackageType(`package`.`type`))

      result <- db.run(query.transactionally).toEitherT
    } yield result
  }

  def count(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    FutureEitherHelpers
      .assert[CoreError](`package`.`type`.isInstanceOf[Imaging])(
        UnsupportedPackageType(`package`.`type`)
      )
      .flatMap { _ =>
        db.run(
            table
              .getByPackageId(`package`.id)
              .length
              .result
          )
          .toEitherT
      }
  }

}
