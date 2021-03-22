package com.blackfynn.models

import com.blackfynn.dtos.ExtendedPackageDTO

final case class PackagesPage(
  packages: Seq[ExtendedPackageDTO],
  cursor: Option[String]
)
