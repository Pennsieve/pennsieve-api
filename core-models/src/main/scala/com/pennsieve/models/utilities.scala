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

package com.pennsieve.models

import org.apache.commons.io.FilenameUtils

package object Utilities {

  /**
    * isNameValid should check if file/package name is valid.
    * Currently, we do not have any restrictions. Escaping for S3 is handled
    * separately with cleanS3Key method in core-models/Utilities
    *
    * TODO: Add meaningful restrictions such as length and maybe path characters?
    *
    * @param name
    * @return
    */
  def isNameValid(name: String): Boolean = {
    true
  }

  def getPennsieveExtension(fileName: String): String = {
    // if one exists, return the first extension from the map that the file name ends with
    // otherwise return no extension (the empty string)
    val extensions = FileExtensions.fileTypeMap.keys
      .filter(extension => fileName.toLowerCase.endsWith(extension))

    extensions.foldLeft("") { (current, next) =>
      if (next.length() > current.length()) next
      else current
    }
  }

  def getFullExtension(fileName: String): Option[String] = {
    //returns the first extension from the map that the file name ends with
    //otherwise try using the apache get extension to get one, if no extension is found, returns None
    val regex = "^\\.".r
    val maybeExtension = getPennsieveExtension(fileName).trim
    maybeExtension match {
      case "" =>
        FilenameUtils.getExtension(fileName).trim match {
          case "" => None
          case otherExtension => Some(otherExtension)
        }
      case pennsieveExtension =>
        Some(regex.replaceFirstIn(pennsieveExtension, ""))
    }
  }

  /**
    * Generate a clean S3 key from a string. This is used to map file-names and
    * folder names to s3 key components
    *
    * @param key
    * @return
    */
  def cleanS3Key(key: String): String = {
    if (key == ".") {
      "%2E"
    } else if (key == "..")
      "%2E%2E"
    else {
      val result =
        """[^\p{L}\p{N}()*_\-'.!]""".r
          .replaceAllIn(key, "_")

      result.substring(0, Math.min(result.length, 128))
    }

  }

//    key.replaceAll("[^a-zA-Z0-9./@-]", "_")

//  def escapeName(name: String): String = {
//    if (name == ".")
//      "%2E"
//    else if (name == "..")
//      "%2E%2E"
//    else if (isNameValid(name))
//      name
//    else {
//      val result = "\\s+".r
//        .replaceAllIn(name, " ")
//        // escape % signs
//        .replaceAll("%(?![2-9A-F][0-9A-F])", "%25")
//        // plus sign are not allowed by S3
//        .replaceAll("\\+", "%2B")
//        // tildes are not allowed by S3
//        .replaceAll("~", "%7E")
//        // the following characters  are not allowed by our naming convention
//        .replaceAll(":", "%3A")
//        .replaceAll(",", "%2C")
//        .replaceAll("@", "%40")
//        .replaceAll("\\$", "%24")
//        .replaceAll("&", "%26")
//        .replaceAll("=", "%3D")
//        .replaceAll(";", "%3B")
//        .replaceAll("/", "%2F")
//        .replaceAll("\\{", "%7B")
//        .replaceAll("}", "%7D")
//        .replaceAll("\\[", "%5B")
//        .replaceAll("]", "%5D")
//        .replaceAll("\\|", "%7C")
//        .replaceAll("#", "%23")
//        .replaceAll("\\^", "%5E")
//        .replaceAll("<", "%3C")
//        .replaceAll(">", "%3E")
//        .replaceAll("\\?", "%3F")
//        .replaceAll("\\\\", "%5C")
//        .replaceAll("`", "%60")
//
//      // Replace % characters not valid escape codes.
//      val escapedString = "^[^\\p{L}\\p{N}() %*_\\-'.!]".r
//        .replaceAllIn(result, "_")
//
//      //trim string if exceeds 255 characters
//      escapedString.substring(0, Math.min(escapedString.length, 255))
//
//    }
//
//  }
}
