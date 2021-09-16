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

import com.google.common.net.UrlEscapers
import org.apache.commons.io.FilenameUtils

package object Utilities {

  def isNameValid(name: String, isEscaped: Boolean = false): Boolean = {
    var AuthorizedCharacters =
      "^[\u00C0-\u1FFF\u2C00-\uD7FF\\w() *_\\-'.!]{1,255}$".r
    //matches all "letter" characters from unicode plus digits and some special characters that are allowed by S3 for keys

    if (isEscaped)
      AuthorizedCharacters =
        "^[\u00C0-\u1FFF\u2C00-\uD7FF\\w() %*_\\-'.!]{1,255}$".r
    // % is technically not an authorized character by itself but the second regex takes care of that special case

    val allCharactersValid: Boolean =
      AuthorizedCharacters.pattern
        .matcher(name)
        .matches

    val multipleContiguousSpaces = " {2,}".r

    val severalWhiteSpacesInARow = multipleContiguousSpaces.findFirstIn(name)

    val ForbiddenPercentSign = "%(?![2-9A-F][0-9A-F])".r
    //negative lookahead: this regex will match any % that is not followed by an hexadecimal code for an html entity (between 20 and FF)

    val badPercentSignDetected
      : Option[String] = ForbiddenPercentSign findFirstIn name
    //since we're using a negative lookahead, the elements won't be captured the same way and we cannot use the same method to test if the regex found something

    allCharactersValid && !badPercentSignDetected.isDefined && !severalWhiteSpacesInARow.isDefined
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
    val regex = "^\\." r
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

  def escapeName(name: String): String = {
    if (name == ".")
      "%2E"
    else if (name == "..")
      "%2E%2E"
    else if (isNameValid(name, true))
      name
    else {
      val result = "\\s+".r
        .replaceAllIn(name, " ")
        // escape % signs
        .replaceAll("%", "%25")
        // plus sign are not allowed by S3
        .replaceAll("\\+", "%2B")
        // tildes are not allowed by S3
        .replaceAll("~", "%7E")
        // the following characters  are not allowed by our naming convention
        .replaceAll(":", "%3A")
        .replaceAll(",", "%2C")
        .replaceAll("@", "%40")
        .replaceAll("\\$", "%24")
        .replaceAll("&", "%26")
        .replaceAll("=", "%3D")
        .replaceAll(";", "%3B")
        .replaceAll("/", "%2F")
        .replaceAll("\\{", "%7B")
        .replaceAll("}", "%7D")
        .replaceAll("\\[", "%5B")
        .replaceAll("]", "%5D")
        .replaceAll("\\|", "%7C")
        .replaceAll("#", "%23")
        .replaceAll("\\^", "%5E")
        .replaceAll("<", "%3C")
        .replaceAll(">", "%3E")
        .replaceAll("\\?", "%3F")
        .replaceAll("\\\\", "%5C")

      // Replace % characters not valid escape codes.
      val escapedString = "^[^(\u00C0-\u1FFF\u2C00-\uD7FF\\w() %*_\\-'.!)]".r
        .replaceAllIn(result, "_")

      //trim string if exceeds 255 characters
      escapedString.substring(0, Math.min(escapedString.length, 255))

    }

  }
}
