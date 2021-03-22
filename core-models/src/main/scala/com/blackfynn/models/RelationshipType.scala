// Copyright (c) 2021 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.models

import enumeratum.{ CirceEnum, Enum, EnumEntry }
import enumeratum.EnumEntry.Camelcase

import scala.collection.immutable

sealed abstract class RelationshipType(override val entryName: String)
    extends EnumEntry

object RelationshipType
    extends Enum[RelationshipType]
    with CirceEnum[RelationshipType] {

  val values: immutable.IndexedSeq[RelationshipType] = findValues

  //case object Cites extends RelationshipType("Cites")
  //case object Continues extends RelationshipType("Continues")
  case object Describes extends RelationshipType("Describes")
  case object Documents extends RelationshipType("Documents")
  //case object HasMetadata extends RelationshipType("HasMetadata")
  //case object IsCitedBy extends RelationshipType("IsCitedBy")
  // case object IsCompiledBy extends RelationshipType("IsCompiledBy")
  //case object IsContinuedBy extends RelationshipType("IsContinuedBy")
  case object IsDerivedFrom extends RelationshipType("IsDerivedFrom")
  case object IsDescribedBy extends RelationshipType("IsDescribedBy")
  case object IsDocumentedBy extends RelationshipType("IsDocumentedBy")
  case object IsMetadataFor extends RelationshipType("IsMetadataFor")
  case object IsReferencedBy extends RelationshipType("IsReferencedBy")
  case object IsRequiredBy extends RelationshipType("IsRequiredBy")
  case object IsSourceOf extends RelationshipType("IsSourceOf")
  case object IsSupplementedBy extends RelationshipType("IsSupplementedBy")
  case object IsSupplementTo extends RelationshipType("IsSupplementTo")
  case object IsOriginalFormOf extends RelationshipType("IsOriginalFormOf")
  case object IsVariantFormOf extends RelationshipType("IsVariantFormOf")
  case object References extends RelationshipType("References")
  case object Requires extends RelationshipType("Requires")
}
