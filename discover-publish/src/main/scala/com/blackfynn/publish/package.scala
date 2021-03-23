package com.pennsieve.publish

import org.apache.commons.lang3.StringUtils
import com.pennsieve.models.Utilities._

package object utils {

  /**
    * Join two S3 keys, removing intervening whitespace and leading slashes.
    */
  def joinKeys(keyPrefix: String, keySuffix: String): String =
    StringUtils.removeStart(
      StringUtils.appendIfMissing(keyPrefix, "/") +
        StringUtils.removeStart(keySuffix, "/"),
      "/"
    )

  def joinKeys(keys: Seq[String]): String =
    keys.fold("")(joinKeys)

}
