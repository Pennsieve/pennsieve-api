package com.blackfynn.publish

import com.blackfynn.models.{ ExternalId, Package }

import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

package object models {

  /**
    * Mapping of package -> path components of the directory containing the
    * package, excluding the package itself. For example, for the following structure:
    *
    *     ├── Brain studies
    *     |   ├── 2018/11/23
    *     |       └── Brain 01
    *
    * The PackagePath for `Brain 01` is (Package("Brain 01"), Seq("Brain studies",
    * "2018/11/23"))
    */
  type PackagePath = (Package, Seq[String])

  /**
    * Mapping of package external IDs to the S3 path to the package.
    * For each package, the map contains one entry for the node id, and
    * one entry for the int id.
    */
  type PackageExternalIdMap = Map[ExternalId, String]
}
