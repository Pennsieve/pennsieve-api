package com.blackfynn.models

import com.google.common.net.UrlEscapers
import org.apache.commons.io.FilenameUtils

package object Utilities {

  def isNameValid(name: String): Boolean = {
    val AuthorizedCharacters =
      "^[\u00C0-\u1FFF\u2C00-\uD7FF\\w() %*_\\-'.!]{1,255}$".r
    //matches all "letter" characters from unicode plus digits and some special characters that are allowed by S3 for keys
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
    else if (isNameValid(name))
      name
    else
      UrlEscapers
        .urlPathSegmentEscaper()
        // S3 keys should only have one consecutive whitespace
        .escape("\\s+".r.replaceAllIn(name, " "))
        // this whitespace should not be encoded
        .replaceAll("%20", " ")
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

  }
}
