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
import com.pennsieve.models.FileType._

object FileExtensions {

  val fileTypeMap: Map[String, FileType] = Map(
    ".bfannot" -> FileType.BFANNOT,
    ".bfts" -> FileType.BFTS,
    // Image files
    ".png" -> FileType.PNG,
    ".jpg" -> FileType.JPEG,
    ".jpeg" -> FileType.JPEG,
    ".jp2" -> FileType.JPEG2000,
    ".jpx" -> FileType.JPEG2000,
    ".lsm" -> FileType.LSM,
    ".ndpi" -> FileType.NDPI,
    ".oib" -> FileType.OIB,
    ".oif" -> FileType.OIF,
    ".ome.tiff" -> FileType.OMETIFF,
    ".ome.tif" -> FileType.OMETIFF,
    ".ome.tf2" -> FileType.OMETIFF,
    ".ome.tf8" -> FileType.OMETIFF,
    ".ome.btf" -> FileType.OMETIFF,
    ".ome.xml" -> FileType.OMETIFF,
    ".brukertiff.gz" -> FileType.BRUKERTIFF,
    ".tiff" -> FileType.TIFF,
    ".tif" -> FileType.TIFF,
    ".gif" -> FileType.GIF,
    ".ai" -> FileType.AdobeIllustrator,
    ".svg" -> FileType.AdobeIllustrator,
    ".nd2" -> FileType.Nikon,
    ".lif" -> FileType.Leica,
    ".ims" -> FileType.Imaris,
    // Markup/Text files
    ".txt" -> FileType.Text,
    ".text" -> FileType.Text,
    ".rtf" -> FileType.Text,
    ".html" -> FileType.HTML,
    ".htm" -> FileType.HTML,
    ".csv" -> FileType.CSV,
    ".pdf" -> FileType.PDF,
    ".doc" -> FileType.MSWord,
    ".docx" -> FileType.MSWord,
    ".json" -> FileType.Json,
    ".xls" -> FileType.MSExcel,
    ".xlsx" -> FileType.MSExcel,
    ".xml" -> FileType.XML,
    ".tsv" -> FileType.TSV,
    ".ppt" -> FileType.PowerPoint,
    ".pptx" -> FileType.PowerPoint,
    // Matlab
    ".mat" -> FileType.MAT,
    ".mex" -> FileType.MAT,
    ".m" -> FileType.MATLAB,
    ".fig" -> FileType.MatlabFigure,
    // ------- TimeSeries --------------
    // Mef
    ".mef" -> FileType.MEF,
    ".mefd.gz" -> FileType.MEF3,
    ".edf" -> FileType.EDF,
    ".tdm" -> FileType.TDMS,
    ".tdms" -> FileType.TDMS,
    ".lay" -> FileType.Persyst,
    ".dat" -> FileType.Persyst,
    ".nex" -> FileType.NeuroExplorer,
    ".nex5" -> FileType.NeuroExplorer,
    ".smr" -> FileType.Spike2,
    // Nicolet
    ".e" -> FileType.Nicolet,
    //Open Ephys
    ".continuous" -> FileType.OpenEphys,
    ".spikes" -> FileType.OpenEphys,
    ".events" -> FileType.OpenEphys,
    ".openephys" -> FileType.OpenEphys,
    // NEV
    ".nev" -> FileType.NEV,
    ".ns1" -> FileType.NEV,
    ".ns2" -> FileType.NEV,
    ".ns3" -> FileType.NEV,
    ".ns4" -> FileType.NEV,
    ".ns5" -> FileType.NEV,
    ".ns6" -> FileType.NEV,
    ".nf3" -> FileType.NEV,
    // Moberg
    ".moberg.gz" -> FileType.MobergSeries,
    // Apache Feather
    ".feather" -> FileType.Feather,
    // BIODAC
    ".tab" -> FileType.BIODAC,
    // BioPAC
    ".acq" -> FileType.BioPAC,
    // Intan
    ".rhd" -> FileType.Intan,
    // IV Curve Data
    ".ibw" -> FileType.IVCurveData,
    // LabChart
    ".adicht" -> FileType.LabChart,
    ".adidat" -> FileType.LabChart,
    // Neuralynx
    ".ncs" -> FileType.Neuralynx,
    // PatchMaster
    ".pgf" -> FileType.PatchMaster,
    ".pul" -> FileType.PatchMaster,
    // pClamp
    ".abf" -> FileType.PClamp,
    // ------- Imaging --------------
    ".dcm" -> FileType.DICOM,
    ".dicom" -> FileType.DICOM,
    ".nii" -> FileType.NIFTI,
    ".nii.gz" -> FileType.NIFTI,
    ".nifti" -> FileType.NIFTI,
    ".roi" -> FileType.ROI,
    ".swc" -> FileType.SWC,
    ".mgh" -> FileType.MGH,
    ".mgz" -> FileType.MGH,
    ".mgh.gz" -> FileType.MGH,
    ".mnc" -> FileType.MINC,
    ".img" -> FileType.ANALYZE,
    ".hdr" -> FileType.ANALYZE,
    ".afni" -> FileType.AFNI,
    ".brik" -> FileType.AFNIBRIK,
    ".head" -> FileType.AFNIBRIK,
    ".lh" -> FileType.FreesurferSurface,
    ".rh" -> FileType.FreesurferSurface,
    ".curv" -> FileType.FreesurferSurface,
    ".eps" -> FileType.EPS,
    ".ps" -> FileType.EPS,
    // 2d imaging
    ".svs" -> FileType.Aperio,
    ".czi" -> FileType.CZI,
    // ------- Video --------------
    ".mov" -> FileType.MOV,
    ".mp4" -> FileType.MP4,
    ".ogg" -> FileType.OGG,
    ".ogv" -> FileType.OGG,
    ".webm" -> FileType.WEBM,
    ".avi" -> FileType.AVI,
    // 3D Model
    ".mph" -> FileType.COMSOL,
    ".sldasm" -> FileType.SolidWorks,
    ".slddrw" -> FileType.SolidWorks,
    // Aggregate
    ".hdf" -> FileType.HDF,
    ".hdf4" -> FileType.HDF,
    ".hdf5" -> FileType.HDF5,
    ".h5" -> FileType.HDF5,
    ".h4" -> FileType.HDF,
    ".he2" -> FileType.HDF,
    ".he5" -> FileType.HDF,
    ".mpj" -> FileType.Minitab,
    ".mtw" -> FileType.Minitab,
    ".mgf" -> FileType.Minitab,
    ".nwb" -> FileType.NeuroDataWithoutBorders,
    ".rdata" -> FileType.RData,
    ".zip" -> FileType.ZIP,
    ".tar" -> FileType.ZIP,
    ".tar.gz" -> FileType.ZIP,
    // Flow
    ".fcs" -> FileType.FCS,
    // Genomics
    ".bam" -> FileType.BAM,
    ".bcl" -> FileType.BAM,
    ".bcl.gz" -> FileType.BAM,
    ".fasta" -> FileType.FASTA,
    ".fastq" -> FileType.FASTQ,
    ".vcf" -> FileType.VariantData,
    ".cram" -> FileType.CRAM,
    // Source code
    ".aedt" -> FileType.Ansys,
    ".cpp" -> FileType.CPlusPlus,
    ".js" -> FileType.Javascript,
    ".md" -> FileType.Markdown,
    ".hoc" -> FileType.Neuron,
    ".mod" -> FileType.Neuron,
    ".py" -> FileType.Python,
    ".r" -> FileType.R,
    ".sh" -> FileType.Shell,
    ".tcsh" -> FileType.Shell,
    ".bash" -> FileType.Shell,
    ".zsh" -> FileType.Shell,
    ".yaml" -> FileType.YAML,
    ".yml" -> FileType.YAML,
    // Generic Data
    ".data" -> FileType.Data,
    ".bin" -> FileType.Data,
    ".raw" -> FileType.Data,
    // Other
    "Dockerfile" -> FileType.Docker,
    ".ipynb" -> FileType.Jupyter
  )

}
