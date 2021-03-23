// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import com.pennsieve.domain.{ CoreError, UnsupportedPackageType }
import com.pennsieve.models.{ ExternalFile, Package, PackageType }
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class ExternalFilesManagerSpec extends BaseManagerSpec {

  "creating external files" should "fail if the package type is not external" in {
    val pkg = createPackage(`type` = PackageType.Image)
    val result: Either[CoreError, ExternalFile] =
      externalFileManager()
        .create(
          pkg,
          "file:///home/pennsieve/data/images/cat.jpg",
          Some("garfield")
        )
        .await
    assert(result.isLeft)
    assert(result.left.value == UnsupportedPackageType(pkg.`type`))
  }

  "creating external files" should "succeed if package type is external" in {
    val pkg = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation =
        Some("file:///home/pennsieve/data/vm/Linux_x86.virtualvm"),
      description = Some("Debian")
    )
    val result = externalFileManager().get(pkg).await
    assert(result.isRight)
    assert(
      result.right.value.location == "file:///home/pennsieve/data/vm/Linux_x86.virtualvm"
    )
    assert(result.right.value.description == Some("Debian"))
  }

  "fetching an external file" should "fail if the package type is not external" in {
    val pkg1 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val pkg2 = createPackage(`type` = PackageType.Image)
    val m = externalFileManager()
    val fetched = m.get(pkg2).await
    assert(fetched.left.value == UnsupportedPackageType(pkg2.`type`))
  }

  "updating an external file" should "fail if the package type is not external" in {
    val pkg1 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val pkg2 = createPackage(`type` = PackageType.Image)
    val m = externalFileManager()
    val externalFile = m.get(pkg1).await.right.value
    val fetched = m.update(pkg2, externalFile).await
    assert(fetched.isLeft)
    assert(fetched.left.value == UnsupportedPackageType(pkg2.`type`))
  }

  "updating an external file" should "fail for an unrelated package" in {
    val pkg1 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val pkg2 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/bar.dat")
    )
    val m = externalFileManager()
    val externalFile = m.get(pkg1).await.right.value
    val fetched = m.update(pkg2, externalFile).await
    assert(fetched.isLeft)
    assert(
      fetched.left.value.toString.toLowerCase
        .contains("duplicate key")
    )
  }

  "updating an external file" should "succeed for the originating package" in {
    val pkg1 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val pkg2 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/bar.dat")
    )
    val m = externalFileManager()
    val externalFile = m.get(pkg1).await.right.value
    val fetched = m.update(pkg1, externalFile).await
    assert(fetched.isRight)
    assert(fetched.right.value == externalFile)
  }

  "delete an external file" should "fail if the package type is not external" in {
    val pkg1 = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val pkg2 = createPackage(`type` = PackageType.Image)
    val m = externalFileManager()
    val deleted = m.delete(pkg2).await
    assert(deleted.isLeft)
    assert(deleted.left.value == UnsupportedPackageType(pkg2.`type`))
  }

  "deleting an external file" should "succeed for the originating package" in {
    val pkg = createPackage(
      `type` = PackageType.ExternalFile,
      externalLocation = Some("file:///home/pennsieve/data/foo.dat")
    )
    val m = externalFileManager()
    m.delete(pkg).await
    val fetched = m.get(pkg).await
    assert(fetched.isLeft) // error to lookout non-existent external file
  }
}
