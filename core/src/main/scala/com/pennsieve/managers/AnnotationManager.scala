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

package com.pennsieve.managers

import com.pennsieve.db._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{
  Annotation,
  AnnotationLayer,
  ModelProperty,
  Organization,
  Package,
  PathElement,
  User
}
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.domain.{ CoreError, Error, IntegrityError, NotFound }
import org.postgresql.util.PSQLException
import slick.dbio.{ DBIOAction, Effect, NoStream }
import slick.sql.FixedSqlAction

import scala.collection.compat._
import scala.concurrent.{ ExecutionContext, Future }

class AnnotationManager(organization: Organization, db: Database) {

  val packages = new PackagesMapper(organization)
  val annotations = new AnnotationsMapper(organization)
  val annotation_layers = new AnnotationLayersMapper(organization)

  def run[R](
    action: DBIOAction[R, NoStream, Nothing]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, R] =
    db.run(action).toEitherT

  def createLayer(
    `package`: Package,
    name: String,
    color: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    run {
      val layer = AnnotationLayer(name, `package`.id, color)

      (annotation_layers returning annotation_layers.map(_.id) += layer)
        .map(id => layer.copy(id = id))
    }

  def updateLayer(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    run {
      annotation_layers
        .filter(_.id === layer.id)
        .update(layer)
        .map(_ => layer)
    }

  def create(
    creator: User,
    layer: AnnotationLayer,
    description: String,
    path: List[PathElement] = Nil,
    properties: List[ModelProperty] = Nil
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] =
    run {
      val annotation =
        Annotation(creator.id, layer.id, description, path, properties)

      (annotations returning annotations.map(_.id) += annotation)
        .map(id => annotation.copy(id = id))
    }

  def deleteLayer(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    run {
      annotation_layers
        .filter(_.id === layer.id)
        .delete
    }

  def update(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] =
    run {
      annotations.filter(_.id === annotation.id).update(annotation).map { _ =>
        annotation
      }
    }

  def delete(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    db.run {
        annotations
          .filter(_.id === annotation.id)
          .delete
      }
      .toEitherT[CoreError] {
        //23503 == postgres foreign_key_violation https://www.postgresql.org/docs/9.6/static/errcodes-appendix.html
        case ex: PSQLException if ex.getSQLState == "23503" =>
          IntegrityError(ex.getMessage): CoreError
        case err => Error(err.getMessage): CoreError
      }

  def find(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Annotation]] =
    run {
      annotations
        .filter(_.layerId === layer.id)
        .result
    }

  def find(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[AnnotationLayer, Seq[Annotation]]] =
    run {
      annotation_layers
        .filter(_.packageId === `package`.id)
        .joinLeft(annotations)
        .on(_.id === _.layerId)
        .result
    }.map { results =>
      results
        .groupBy(_._1)
        .view
        .mapValues(values => values.flatMap(_._2))
        .toMap // toMap may show as redundant, but is for Scala 2.13
    }

  def findLayers(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[AnnotationLayer]] =
    run {
      annotation_layers
        .filter(_.packageId === `package`.id)
        .result
    }

  def copyAnnotation(
    annotation: Annotation,
    newLayer: AnnotationLayer
  ): FixedSqlAction[Annotation, NoStream, Effect.Write] =
    annotations returning annotations += annotation.copy(layerId = newLayer.id)

  def copyLayer(
    layer: AnnotationLayer,
    destination: Package
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[
    AnnotationLayer,
    NoStream,
    Effect.Write with Effect.Read with Effect.Write
  ] = {
    for {
      newLayer <- annotation_layers returning annotation_layers += layer.copy(
        packageId = destination.id
      )
      annotations <- annotations.filter(_.layerId === layer.id).result
      _ <- DBIO.sequence(annotations.map(a => copyAnnotation(a, newLayer)))
    } yield newLayer
  }

  def copyPackageAnnotations(
    source: Package,
    destination: Package
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[
    Unit,
    NoStream,
    Effect.Read with Effect.Write with Effect.Read with Effect.Write with Effect.Transactional
  ] = {
    val copy = for {
      layers <- annotation_layers.filter(_.packageId === source.id).result
      _ <- DBIO.sequence(layers.map(l => copyLayer(l, destination)))
    } yield ()
    copy.transactionally
  }

  def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] =
    db.run {
        annotations
          .filter(_.id === id)
          .result
          .headOption
      }
      .whenNone[CoreError](NotFound(s"Annotation ($id)"))

  def findAnnotationUsersForPackage(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {
    run {
      val matchingLayers = annotation_layers
        .filter(_.packageId === `package`.id)
        .map(_.id)

      val matchingUserIds = annotations
        .filter(_.layerId in matchingLayers)
        .map(_.creatorId)

      UserMapper
        .filter(_.id in matchingUserIds)
        .result
    }
  }

  def getLayer(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    db.run(
        annotation_layers
          .filter(_.id === id)
          .result
          .headOption
      )
      .whenNone[CoreError](NotFound(s"Layer ($id)"))

  def getLayerByPackage(
    id: Int,
    packageId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    db.run(
        annotation_layers
          .filter(_.id === id)
          .filter(_.packageId === packageId)
          .result
          .headOption
      )
      .whenNone[CoreError](
        NotFound(s"Layer ($id) belonging to package ($packageId)")
      )

  def findUsersForAnnotation(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] =
    run {
      val matchingUserIds = annotations
        .filter(_.layerId === annotation.layerId)
        .map(_.creatorId)

      UserMapper
        .filter(_.id in matchingUserIds)
        .result
    }

  //not returning the package because annotations are not secured
  def findPackageId(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    db.run {
        val query = for {
          (p, l) <- packages join annotation_layers.filter(
            _.id === annotation.layerId
          ) on (_.id === _.packageId)
        } yield p.id

        query.result.headOption
      }
      .whenNone[CoreError](NotFound(s"Layer (${annotation.layerId})"))

}
