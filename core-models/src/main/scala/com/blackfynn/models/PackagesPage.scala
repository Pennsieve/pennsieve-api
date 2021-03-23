package com.pennsieve.models

import com.pennsieve.dtos.ExtendedPackageDTO

final case class PackagesPage(
  packages: Seq[ExtendedPackageDTO],
  cursor: Option[String]
)
