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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.domain.{ CoreError, NotFound }
import com.pennsieve.managers.AnnotationManager
import com.pennsieve.models.{
  Annotation,
  AnnotationLayer,
  ModelProperty,
  Organization,
  Package,
  PathElement,
  User
}
import com.pennsieve.traits.PostgresProfile.api.Database

import scala.concurrent.{ ExecutionContext, Future }

class FakeAnnotationManager(
  state: InMemoryState,
  override val organization: Organization
) extends AnnotationManager {

  def db: Database =
    sys.error(
      "FakeAnnotationManager: a method not yet stubbed by your test tried " +
        "to use the database. Override the method on this fake."
    )

  override def createLayer(
    `package`: Package,
    name: String,
    color: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] = {
    val id = state.newId()
    val layer = AnnotationLayer(name, `package`.id, color, id = id)
    state.annotationLayers.put((organization.id, id), layer)
    EitherT.rightT(layer)
  }

  override def updateLayer(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] = {
    state.annotationLayers.put((organization.id, layer.id), layer)
    EitherT.rightT(layer)
  }

  override def deleteLayer(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.annotationLayers.remove((organization.id, layer.id))
    // Cascade-delete annotations belonging to the removed layer.
    state.annotations
      .collect {
        case ((o, aid), a) if o == organization.id && a.layerId == layer.id =>
          aid
      }
      .foreach(aid => state.annotations.remove((organization.id, aid)))
    EitherT.rightT(1)
  }

  override def getLayer(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    state.annotationLayers.get((organization.id, id)) match {
      case Some(l) => EitherT.rightT(l)
      case None => EitherT.leftT(NotFound(s"Layer ($id)"): CoreError)
    }

  override def getLayerByPackage(
    id: Int,
    packageId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, AnnotationLayer] =
    state.annotationLayers.get((organization.id, id)) match {
      case Some(l) if l.packageId == packageId => EitherT.rightT(l)
      case _ =>
        EitherT.leftT(
          NotFound(s"Layer ($id) belonging to package ($packageId)"): CoreError
        )
    }

  override def findLayers(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[AnnotationLayer]] =
    EitherT.rightT(layersForPackage(`package`.id))

  override def find(
    layer: AnnotationLayer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[Annotation]] =
    EitherT.rightT(
      state.annotations
        .collect {
          case ((o, _), a) if o == organization.id && a.layerId == layer.id =>
            a
        }
        .toSeq
        .sortBy(_.id)
    )

  override def find(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Map[AnnotationLayer, Seq[Annotation]]] = {
    val layers = layersForPackage(`package`.id)
    val map = layers.map { l =>
      val anns = state.annotations
        .collect {
          case ((o, _), a) if o == organization.id && a.layerId == l.id => a
        }
        .toSeq
        .sortBy(_.id)
      l -> anns
    }.toMap
    EitherT.rightT(map)
  }

  override def create(
    creator: User,
    layer: AnnotationLayer,
    description: String,
    path: List[PathElement] = Nil,
    properties: List[ModelProperty] = Nil
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] = {
    val id = state.newId()
    val ann =
      Annotation(
        creatorId = creator.id,
        layerId = layer.id,
        description = description,
        path = path,
        attributes = properties,
        id = id
      )
    state.annotations.put((organization.id, id), ann)
    EitherT.rightT(ann)
  }

  override def update(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] = {
    state.annotations.put((organization.id, annotation.id), annotation)
    EitherT.rightT(annotation)
  }

  override def delete(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] = {
    state.annotations.remove((organization.id, annotation.id))
    EitherT.rightT(1)
  }

  override def get(
    id: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Annotation] =
    state.annotations.get((organization.id, id)) match {
      case Some(a) => EitherT.rightT(a)
      case None =>
        EitherT.leftT(NotFound(s"Annotation ($id)"): CoreError)
    }

  override def findPackageId(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    state.annotationLayers.get((organization.id, annotation.layerId)) match {
      case Some(l) => EitherT.rightT(l.packageId)
      case None =>
        EitherT.leftT(NotFound(s"Layer (${annotation.layerId})"): CoreError)
    }

  override def findUsersForAnnotation(
    annotation: Annotation
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {
    val creatorIds = state.annotations.collect {
      case ((o, _), a)
          if o == organization.id && a.layerId == annotation.layerId =>
        a.creatorId
    }.toSet
    EitherT.rightT(creatorIds.flatMap(state.users.get).toSeq.sortBy(_.id))
  }

  override def findAnnotationUsersForPackage(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[User]] = {
    val layerIds = layersForPackage(`package`.id).map(_.id).toSet
    val creatorIds = state.annotations.collect {
      case ((o, _), a)
          if o == organization.id && layerIds.contains(a.layerId) =>
        a.creatorId
    }.toSet
    EitherT.rightT(creatorIds.flatMap(state.users.get).toSeq.sortBy(_.id))
  }

  private def layersForPackage(packageId: Int): Seq[AnnotationLayer] =
    state.annotationLayers
      .collect {
        case ((o, _), l) if o == organization.id && l.packageId == packageId =>
          l
      }
      .toSeq
      .sortBy(_.id)
}
