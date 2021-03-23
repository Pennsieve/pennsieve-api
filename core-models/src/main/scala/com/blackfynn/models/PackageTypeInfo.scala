package com.pennsieve.models

object PackageTypeInfo {

  /**
    * Given a package type, provide a set of file types the package can be exported to.
    * @return
    */
  def exportableTo: PackageType => Set[FileType] = {
    case PackageType.TimeSeries => Set(FileType.NeuroDataWithoutBorders)
    case _ => Set.empty
  }

  /**
    * Given a package type, provide a set of file types the package can be exported to.
    * @return
    */
  def canExportTo(packageType: PackageType, targetFileType: FileType): Boolean =
    exportableTo(packageType).contains(targetFileType)
}
