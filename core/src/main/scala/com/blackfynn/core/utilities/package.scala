// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.core

import cats.data.EitherT
import cats.implicits._
import com.blackfynn.domain.{ CoreError, Error, NameCheckError, PredicateError }
import com.blackfynn.models.FileExtensions.fileTypeMap
import com.blackfynn.models.{ FileType, Organization }
import com.blackfynn.models.FileType.GenericData
import com.blackfynn.models.Utilities.{
  escapeName,
  getFullExtension,
  getPennsieveExtension
}
import io.circe.shapes._
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import java.util.UUID

import com.google.common.net.UrlEscapers
import org.apache.commons.io.FilenameUtils

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }

package object utilities {

  def encryptionKey(organization: Organization): Either[CoreError, String] = {
    organization.encryptionKeyId
      .map(key => Right(key))
      .getOrElse {
        Left(Error(s"${organization.name} organization has no encryption key"))
      }
  }

  def getFileType: String => FileType = fileTypeMap getOrElse (_, GenericData)

  def splitFileName(fileName: String): (String, String) = {
    // if one exists, return the first extension from the map that the file name ends with
    // otherwise return no extension (the empty string)
    val extension = getPennsieveExtension(fileName)
    // strip the extension and directories off the file name
    val baseName = FilenameUtils.getName(fileName.dropRight(extension.length))

    (baseName, extension)
  }

  //this is "takeWhile" inclusive of the last item, which matches the predicate
  def takeUntil[A](l: List[A], predicate: (A => Boolean)): List[A] = {
    l.span(i => predicate(i)) match { case (h, t) => h ::: t.take(1) }
  }

  def check(
    predicate: => Boolean
  )(
    errorMessage: String,
    coreErrorBuilder: String => CoreError = PredicateError
  ): Either[CoreError, Boolean] = {
    val result = predicate
    if (result) Right(result)
    else Left(coreErrorBuilder(errorMessage))
  }

  def checkOrError[T](predicate: => Boolean)(error: T): Either[T, Boolean] = {
    val result = predicate
    if (result) Right(result)
    else Left(error)
  }

  def checkOrErrorT[T](
    predicate: => Boolean
  )(
    error: T
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, T, Boolean] = {
    val result = predicate
    val either =
      if (result) Right(result)
      else Left(error)

    either.toEitherT[Future]
  }

  @tailrec
  def recommendName(
    name: String,
    children: Set[String],
    index: Int = 1,
    duplicateThreshold: Int = 100
  ): Either[NameCheckError, String] = {
    val childNames = children.map(_.toLowerCase)
    val validatedName = escapeName(name)
    val extension = getFullExtension(validatedName) match {
      case Some(extension) => "." + extension
      case None => ""
    }
    val baseName =
      FilenameUtils.getName(validatedName.dropRight(extension.length))

    if (index > duplicateThreshold) {
      // too many duplicates, stop recursion
      val recommendation = baseName + " (" + java.util.UUID
        .randomUUID()
        .toString + ")" + extension
      Left(NameCheckError(recommendation))
    } else if (childNames.contains(validatedName.toLowerCase)) {
      // try next index
      val recommendation = baseName + " (" + index + ")" + extension
      if (childNames.contains(recommendation.toLowerCase)) {
        recommendName(validatedName, children, index + 1, duplicateThreshold)
      } else {
        Left(NameCheckError(recommendation))
      }
    } else if (validatedName != name) {
      Left(NameCheckError(validatedName))
    } else {
      Right(validatedName)
    }
  }

  def checkAndNormalizeInitial(
    middleInitial: Option[String]
  ): Either[CoreError, Option[String]] = middleInitial.map(_.trim) match {
    case Some(mi) if mi.length == 1 => Right(Some(mi.toUpperCase))
    case None | Some("") => Right(None)
    case _ => Left(PredicateError("Middle Initial can only be one character"))
  }
}
