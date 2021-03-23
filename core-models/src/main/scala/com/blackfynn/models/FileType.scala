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

import enumeratum._

import scala.collection.immutable.IndexedSeq

sealed trait FileType extends EnumEntry

object FileType extends Enum[FileType] with CirceEnum[FileType] {
  val values: IndexedSeq[FileType] = findValues

  case object PDF extends FileType
  case object MEF extends FileType
  case object EDF extends FileType
  case object TDMS extends FileType
  case object OpenEphys extends FileType
  case object Persyst extends FileType
  case object DICOM extends FileType
  case object NIFTI extends FileType
  case object PNG extends FileType
  case object BCI2000 extends FileType
  case object BNI1 extends FileType
  case object WFDB extends FileType
  case object CZI extends FileType
  case object Aperio extends FileType
  case object StaticViewerData extends FileType
  case object DirectoryViewerData extends FileType
  case object Json extends FileType
  case object CSV extends FileType
  case object TSV extends FileType
  case object Text extends FileType
  case object XML extends FileType
  case object HTML extends FileType
  case object MSExcel extends FileType
  case object MSWord extends FileType
  case object MP4 extends FileType
  case object WEBM extends FileType
  case object OGG extends FileType
  case object MOV extends FileType
  case object MAT extends FileType
  case object JPEG extends FileType
  case object JPEG2000 extends FileType
  case object LSM extends FileType
  case object NDPI extends FileType
  case object OIB extends FileType
  case object OIF extends FileType
  case object ROI extends FileType
  case object SWC extends FileType
  case object CRAM extends FileType
  case object MGH extends FileType
  case object AVI extends FileType
  case object MATLAB extends FileType
  case object HDF5 extends FileType
  case object TIFF extends FileType
  case object OMETIFF extends FileType
  case object BRUKERTIFF extends FileType
  case object GIF extends FileType
  case object ANALYZE extends FileType
  case object NeuroExplorer extends FileType
  case object MINC extends FileType
  case object Blackrock extends FileType
  case object MobergSeries extends FileType
  case object GenericData extends FileType
  case object BFANNOT extends FileType
  case object BFTS extends FileType
  case object Nicolet extends FileType
  case object MEF3 extends FileType
  case object Feather extends FileType
  case object NEV extends FileType
  case object Spike2 extends FileType

  case object AdobeIllustrator extends FileType
  case object AFNI extends FileType
  case object AFNIBRIK extends FileType
  case object Ansys extends FileType
  case object BAM extends FileType
  case object BIODAC extends FileType
  case object BioPAC extends FileType
  case object COMSOL extends FileType
  case object CPlusPlus extends FileType
  case object CSharp extends FileType
  case object Data extends FileType
  case object Docker extends FileType
  case object EPS extends FileType
  case object FCS extends FileType
  case object FASTA extends FileType
  case object FASTQ extends FileType
  case object FreesurferSurface extends FileType
  case object FSFast extends FileType
  case object HDF extends FileType
  case object Imaris extends FileType
  case object Intan extends FileType
  case object IVCurveData extends FileType
  case object JAVA extends FileType
  case object Javascript extends FileType
  case object Jupyter extends FileType
  case object LabChart extends FileType
  case object Leica extends FileType
  case object MatlabFigure extends FileType
  case object Markdown extends FileType
  case object Minitab extends FileType
  case object Neuralynx extends FileType
  case object NeuroDataWithoutBorders extends FileType
  case object Neuron extends FileType
  case object NihonKoden extends FileType
  case object Nikon extends FileType
  case object PatchMaster extends FileType
  case object PClamp extends FileType
  case object Plexon extends FileType
  case object PowerPoint extends FileType
  case object Python extends FileType
  case object R extends FileType
  case object RData extends FileType
  case object Shell extends FileType
  case object SolidWorks extends FileType
  case object VariantData extends FileType
  case object YAML extends FileType
  case object ZIP extends FileType

  override lazy val namesToValuesMap: Map[String, FileType] =
    values.map(v => v.entryName -> v).toMap + ("FACS" -> FCS)
}
