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

import com.pennsieve.models.FileType.{
  AdobeIllustrator,
  Ansys,
  BAM,
  CPlusPlus,
  CSharp,
  GenericData,
  HDF,
  Javascript,
  MSExcel,
  MSWord,
  Markdown,
  PowerPoint,
  Python,
  R,
  RData,
  YAML,
  _
}
import com.pennsieve.models.Icon.{
  Code,
  Flow,
  Generic,
  Image,
  JSON,
  Matlab,
  Model,
  Notebook,
  Tabular,
  Timeseries,
  _
}
import com.pennsieve.models.PackageType.Unsupported
import com.pennsieve.models.FileTypeGrouping._
import scala.collection.immutable.{ Map, Set }

sealed trait FileTypeGrouping

object FileTypeGrouping {
  case object Individual extends FileTypeGrouping
  case object Annotation extends FileTypeGrouping
  case object ByTypeByName extends FileTypeGrouping
  case object ByType extends FileTypeGrouping
}

case class FileTypeInfo(
  fileType: FileType,
  packageType: PackageType,
  packageSubtype: String,
  masterExtension: Option[String],
  grouping: FileTypeGrouping,
  validate: Boolean,
  hasWorkflow: Boolean,
  icon: Icon
)

// TODO: Move to configuration file (yaml or json) and parse into Scala in the API
object FileTypeInfo {

  /**
    * Given a FileType, return metadata about the type and its supported operations.
    * @return
    */
  def get: FileType => FileTypeInfo = {
    case MEF =>
      FileTypeInfo(
        fileType = MEF,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = ByType,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case EDF =>
      FileTypeInfo(
        fileType = EDF,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case TDMS =>
      FileTypeInfo(
        fileType = TDMS,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case OpenEphys =>
      FileTypeInfo(
        fileType = OpenEphys,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = ByType,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case Persyst =>
      FileTypeInfo(
        fileType = Persyst,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = Some(".lay"),
        grouping = ByTypeByName,
        validate = true,
        hasWorkflow = true,
        icon = Timeseries
      )
    case NeuroExplorer =>
      FileTypeInfo(
        fileType = NeuroExplorer,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = ByType,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case MobergSeries =>
      FileTypeInfo(
        fileType = MobergSeries,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = ByTypeByName,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case BFTS =>
      FileTypeInfo(
        fileType = BFTS,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case Nicolet =>
      FileTypeInfo(
        fileType = Nicolet,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case MEF3 =>
      FileTypeInfo(
        fileType = MEF3,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Timeseries
      )
    case Feather =>
      FileTypeInfo(
        fileType = Feather,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case NEV =>
      FileTypeInfo(
        fileType = NEV,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = Some(".nev"),
        grouping = ByTypeByName,
        validate = true,
        hasWorkflow = true,
        icon = Timeseries
      )
    case Spike2 =>
      FileTypeInfo(
        fileType = Spike2,
        packageType = PackageType.TimeSeries,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = ByTypeByName,
        validate = true,
        hasWorkflow = true,
        icon = Timeseries
      )

    case MINC =>
      FileTypeInfo(
        fileType = MINC,
        packageType = PackageType.MRI,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = ClinicalImageBrain
      )
    case DICOM =>
      FileTypeInfo(
        fileType = DICOM,
        packageType = PackageType.MRI,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = ByType,
        validate = false,
        hasWorkflow = true,
        icon = ClinicalImageBrain
      )
    case NIFTI =>
      FileTypeInfo(
        fileType = NIFTI,
        packageType = PackageType.MRI,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = ClinicalImageBrain
      )
    case ROI =>
      FileTypeInfo(
        fileType = ROI,
        packageType = Unsupported,
        packageSubtype = "Morphology",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = ClinicalImageBrain
      )
    case SWC =>
      FileTypeInfo(
        fileType = SWC,
        packageType = Unsupported,
        packageSubtype = "Morphology",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = ClinicalImageBrain
      )
    case ANALYZE =>
      FileTypeInfo(
        fileType = ANALYZE,
        packageType = PackageType.MRI,
        packageSubtype = "3D Image",
        masterExtension = Some(".img"),
        grouping = ByTypeByName,
        validate = true,
        hasWorkflow = true,
        icon = ClinicalImageBrain
      )
    case MGH =>
      FileTypeInfo(
        fileType = MGH,
        packageType = PackageType.MRI,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = ClinicalImageBrain
      )

    case JPEG =>
      FileTypeInfo(
        fileType = JPEG,
        packageType = PackageType.Image,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Image
      )
    case PNG =>
      FileTypeInfo(
        fileType = PNG,
        packageType = PackageType.Image,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Image
      )
    case TIFF =>
      FileTypeInfo(
        fileType = TIFF,
        packageType = PackageType.Slide,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Microscope
      )
    case OMETIFF =>
      FileTypeInfo(
        fileType = OMETIFF,
        packageType = PackageType.Slide,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case BRUKERTIFF =>
      FileTypeInfo(
        fileType = BRUKERTIFF,
        packageType = PackageType.Slide,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Microscope
      )
    case CZI =>
      FileTypeInfo(
        fileType = CZI,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case JPEG2000 =>
      FileTypeInfo(
        fileType = JPEG2000,
        packageType = PackageType.Image,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Microscope
      )
    case LSM =>
      FileTypeInfo(
        fileType = LSM,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case NDPI =>
      FileTypeInfo(
        fileType = NDPI,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case OIB =>
      FileTypeInfo(
        fileType = OIB,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case OIF =>
      FileTypeInfo(
        fileType = OIF,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case GIF =>
      FileTypeInfo(
        fileType = GIF,
        packageType = PackageType.Image,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Image
      )

    case WEBM =>
      FileTypeInfo(
        fileType = WEBM,
        packageType = PackageType.Video,
        packageSubtype = "Video",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.Video
      )
    case OGG =>
      FileTypeInfo(
        fileType = OGG,
        packageType = PackageType.Video,
        packageSubtype = "Video",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.Video
      )
    case MOV =>
      FileTypeInfo(
        fileType = MOV,
        packageType = PackageType.Video,
        packageSubtype = "Video",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.Video
      )
    case AVI =>
      FileTypeInfo(
        fileType = AVI,
        packageType = PackageType.Video,
        packageSubtype = "Video",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.Video
      )
    case MP4 =>
      FileTypeInfo(
        fileType = MP4,
        packageType = PackageType.Video,
        packageSubtype = "Video",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.Video
      )

    // TODO: re-enable processing
    case CSV =>
      FileTypeInfo(
        fileType = CSV,
        packageType = PackageType.CSV,
        packageSubtype = "Tabular",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Tabular
      )
    // TODO: re-enable processing
    case TSV =>
      FileTypeInfo(
        fileType = TSV,
        packageType = PackageType.CSV,
        packageSubtype = "Tabular",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Tabular
      )
    case MSExcel =>
      FileTypeInfo(
        fileType = MSExcel,
        packageType = Unsupported,
        packageSubtype = "MS Excel",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Excel
      )

    case Aperio =>
      FileTypeInfo(
        fileType = Aperio,
        packageType = PackageType.Slide,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Microscope
      )

    case MSWord =>
      FileTypeInfo(
        fileType = MSWord,
        packageType = PackageType.MSWord,
        packageSubtype = "MS Word",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Word
      )
    case FileType.PDF =>
      FileTypeInfo(
        fileType = FileType.PDF,
        packageType = PackageType.PDF,
        packageSubtype = "PDF",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.PDF
      )
    case FileType.Text =>
      FileTypeInfo(
        fileType = FileType.Text,
        packageType = PackageType.Text,
        packageSubtype = "Text",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.Text
      )

    case BFANNOT =>
      FileTypeInfo(
        fileType = BFANNOT,
        packageType = PackageType.Unknown,
        packageSubtype = "Text",
        masterExtension = None,
        grouping = FileTypeGrouping.Annotation,
        validate = false,
        hasWorkflow = false,
        icon = Generic
      )

    // Default info for FileTypes we accept but do not (yet) officially support as Packages

    case AdobeIllustrator =>
      FileTypeInfo(
        fileType = AdobeIllustrator,
        packageType = Unsupported,
        packageSubtype = "Illustrator",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.AdobeIllustrator
      )
    case AFNI =>
      FileTypeInfo(
        fileType = AFNI,
        packageType = Unsupported,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = ClinicalImageBrain
      )
    case AFNIBRIK =>
      FileTypeInfo(
        fileType = AFNIBRIK,
        packageType = Unsupported,
        packageSubtype = "3D Image",
        masterExtension = Some(".head"),
        grouping = ByTypeByName,
        validate = false,
        hasWorkflow = false,
        icon = ClinicalImageBrain
      )
    case Ansys =>
      FileTypeInfo(
        fileType = Ansys,
        packageType = Unsupported,
        packageSubtype = "Ansys",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case BAM =>
      FileTypeInfo(
        fileType = BAM,
        packageType = Unsupported,
        packageSubtype = "BAM",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Genomics
      )
    case CRAM =>
      FileTypeInfo(
        fileType = CRAM,
        packageType = Unsupported,
        packageSubtype = "Sequence",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Genomics
      )
    case BIODAC =>
      FileTypeInfo(
        fileType = BIODAC,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case BioPAC =>
      FileTypeInfo(
        fileType = BioPAC,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case COMSOL =>
      FileTypeInfo(
        fileType = COMSOL,
        packageType = Unsupported,
        packageSubtype = "Model",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Model
      )
    case CPlusPlus =>
      FileTypeInfo(
        fileType = CPlusPlus,
        packageType = Unsupported,
        packageSubtype = "C++",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case CSharp =>
      FileTypeInfo(
        fileType = CSharp,
        packageType = Unsupported,
        packageSubtype = "C#",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case Data =>
      FileTypeInfo(
        fileType = GenericData,
        packageType = Unsupported,
        packageSubtype = "Generic",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.GenericData
      )
    case FileType.Docker =>
      FileTypeInfo(
        fileType = FileType.Docker,
        packageType = Unsupported,
        packageSubtype = "Docker",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.Docker
      )
    case EPS =>
      FileTypeInfo(
        fileType = EPS,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Image
      )
    case FCS =>
      FileTypeInfo(
        fileType = FCS,
        packageType = Unsupported,
        packageSubtype = "Flow",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Flow
      )
    case FASTA =>
      FileTypeInfo(
        fileType = FASTA,
        packageType = Unsupported,
        packageSubtype = "Tabular",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Genomics
      )
    case FASTQ =>
      FileTypeInfo(
        fileType = FASTQ,
        packageType = Unsupported,
        packageSubtype = "Tabular",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Genomics
      )
    case FreesurferSurface =>
      FileTypeInfo(
        fileType = FreesurferSurface,
        packageType = Unsupported,
        packageSubtype = "3D Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = ClinicalImageBrain
      )
    case FSFast =>
      FileTypeInfo(
        fileType = FSFast,
        packageType = Unsupported,
        packageSubtype = "Generic",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Generic
      )
    case HDF =>
      FileTypeInfo(
        fileType = FileType.HDF,
        packageType = Unsupported,
        packageSubtype = "Data Container",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.HDF
      )
    case HTML =>
      FileTypeInfo(
        fileType = FileType.HTML,
        packageType = Unsupported,
        packageSubtype = "Code",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case Imaris =>
      FileTypeInfo(
        fileType = Imaris,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case Intan =>
      FileTypeInfo(
        fileType = Intan,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case IVCurveData =>
      FileTypeInfo(
        fileType = IVCurveData,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case JAVA =>
      FileTypeInfo(
        fileType = JAVA,
        packageType = Unsupported,
        packageSubtype = "Java",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case Javascript =>
      FileTypeInfo(
        fileType = Javascript,
        packageType = Unsupported,
        packageSubtype = "Javascript",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case Json =>
      FileTypeInfo(
        fileType = Json,
        packageType = Unsupported,
        packageSubtype = "JSON",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = JSON
      )
    case Jupyter =>
      FileTypeInfo(
        fileType = Jupyter,
        packageType = Unsupported,
        packageSubtype = "Notebook",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Notebook
      )
    case LabChart =>
      FileTypeInfo(
        fileType = LabChart,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case Leica =>
      FileTypeInfo(
        fileType = Leica,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case MAT =>
      FileTypeInfo(
        fileType = MAT,
        packageType = PackageType.HDF5,
        packageSubtype = "Data Container",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Matlab
      )
    case MATLAB =>
      FileTypeInfo(
        fileType = MATLAB,
        packageType = Unsupported,
        packageSubtype = "Matlab",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case MatlabFigure =>
      FileTypeInfo(
        fileType = MatlabFigure,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Image
      )
    case Markdown =>
      FileTypeInfo(
        fileType = Markdown,
        packageType = Unsupported,
        packageSubtype = "Markdown",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case Minitab =>
      FileTypeInfo(
        fileType = Minitab,
        packageType = Unsupported,
        packageSubtype = "Generic",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.GenericData
      )
    case Neuralynx =>
      FileTypeInfo(
        fileType = Neuralynx,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case NeuroDataWithoutBorders =>
      FileTypeInfo(
        fileType = NeuroDataWithoutBorders,
        packageType = PackageType.HDF5,
        packageSubtype = "Data Container",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = NWB
      )
    case Neuron =>
      FileTypeInfo(
        fileType = Neuron,
        packageType = Unsupported,
        packageSubtype = "Code",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case NihonKoden =>
      FileTypeInfo(
        fileType = NihonKoden,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case Nikon =>
      FileTypeInfo(
        fileType = Nikon,
        packageType = Unsupported,
        packageSubtype = "Image",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Microscope
      )
    case PatchMaster =>
      FileTypeInfo(
        fileType = PatchMaster,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case PClamp =>
      FileTypeInfo(
        fileType = PClamp,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case Plexon =>
      FileTypeInfo(
        fileType = Plexon,
        packageType = Unsupported,
        packageSubtype = "Timeseries",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Timeseries
      )
    case PowerPoint =>
      FileTypeInfo(
        fileType = PowerPoint,
        packageType = Unsupported,
        packageSubtype = "MS Powerpoint",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.PowerPoint
      )
    case Python =>
      FileTypeInfo(
        fileType = Python,
        packageType = Unsupported,
        packageSubtype = "Python",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case R =>
      FileTypeInfo(
        fileType = R,
        packageType = Unsupported,
        packageSubtype = "R",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case RData =>
      FileTypeInfo(
        fileType = FileType.RData,
        packageType = Unsupported,
        packageSubtype = "Data Container",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.RData
      )
    case Shell =>
      FileTypeInfo(
        fileType = Shell,
        packageType = Unsupported,
        packageSubtype = "Code",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case SolidWorks =>
      FileTypeInfo(
        fileType = SolidWorks,
        packageType = Unsupported,
        packageSubtype = "Model",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Model
      )
    case VariantData =>
      FileTypeInfo(
        fileType = VariantData,
        packageType = Unsupported,
        packageSubtype = "Tabular",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = GenomicsVariant
      )
    case FileType.XML =>
      FileTypeInfo(
        fileType = FileType.XML,
        packageType = Unsupported,
        packageSubtype = "XML",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Icon.XML
      )
    case YAML =>
      FileTypeInfo(
        fileType = YAML,
        packageType = Unsupported,
        packageSubtype = "YAML",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Code
      )
    case ZIP =>
      FileTypeInfo(
        fileType = ZIP,
        packageType = PackageType.ZIP,
        packageSubtype = "Compressed",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Zip
      )
    case HDF5 =>
      FileTypeInfo(
        fileType = HDF5,
        packageType = PackageType.HDF5,
        packageSubtype = "Data Container",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = true,
        icon = Icon.HDF
      )

    case unknown =>
      FileTypeInfo(
        fileType = unknown,
        packageType = PackageType.Unknown,
        packageSubtype = "Generic",
        masterExtension = None,
        grouping = Individual,
        validate = false,
        hasWorkflow = false,
        icon = Generic
      )

  }

}
