// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.helpers

import com.blackfynn.concepts.types.ProxyRelationshipDirection
import com.blackfynn.doi.models.DoiState
import com.blackfynn.models.PackageType._
import com.blackfynn.models._
import com.blackfynn.timeseries.AnnotationData
import com.blackfynn.aws.email.Email

import enumeratum.Json4s

import io.circe

import java.time.{ LocalDate, OffsetDateTime, ZonedDateTime }
import java.time.format.DateTimeFormatter

import org.json4s.Serializer
import org.json4s.JsonAST.{ JNull, JObject, JString }
import org.json4s.CustomSerializer
import org.json4s.JsonAST._
import org.json4s.ext.{ DateTimeSerializer, URLSerializer, UUIDSerializer }

object ModelSerializers {

  implicit val serializers: Seq[Serializer[_]] = Seq(
    DateTimeSerializer,
    UUIDSerializer,
    URLSerializer,
    AnnotationData.AnnotationDataSerializer,
    Json4s.serializer(FileType),
    Json4s.serializer(FileObjectType),
    PackageTypeSerializer,
    Json4s.serializer(PackageState),
    Json4s.serializer(Degree),
    ZonedDateTimeSerializer,
    OffsetDateTimeSerializer,
    LocalDateSerializer,
    EmailSerializer,
    SerializedCursorSerializer,
    CirceJsonSerializer,
    Json4s.serializer(SubscriptionStatus),
    Json4s.serializer(DBPermission),
    Json4s.serializer(Role),
    Json4s.serializer(Feature),
    Json4s.serializer(DimensionAssignment),
    Json4s.serializer(DatasetType),
    Json4s.serializer(DatasetState),
    Json4s.serializer(PublishStatus),
    Json4s.serializer(OnboardingEventType),
    Json4s.serializer(DefaultDatasetStatus),
    Json4s.serializer(License),
    Json4s.serializer(DoiState),
    Json4s.serializer(PublicationStatus),
    Json4s.serializer(PublicationType),
    Json4s.serializer(SystemTeamType),
    Json4s.serializer(PayloadType),
    Json4s.serializer(ProxyRelationshipDirection),
    Json4s.serializer(EmbargoAccess),
    Json4s.serializer(ChangelogEventName),
    Json4s.serializer(EventGroupPeriod),
    Json4s.serializer(RelationshipType)
  )
}

case object PackageTypeSerializer
    extends CustomSerializer[PackageType](
      _ =>
        ({
          case JString(packageType) => {
            packageType.toUpperCase.trim match {
              // --- SPECIAL CASES ---
              case "EXTERNAL" => PackageType.ExternalFile
              // --- EVERYTHING ELSE ---
              case _ =>
                PackageType.withNameInsensitiveOption(packageType.trim) match {
                  case Some(t) => t
                  case None => Unknown
                }
            }
          }
          case JNull =>
            throw new IllegalArgumentException("unsupported package type: NULL")
        }, {
          case packageType: PackageType =>
            JString(packageType.getClass.getSimpleName.replace("$", ""))
        })
    )

case object ZonedDateTimeSerializer
    extends CustomSerializer[ZonedDateTime](
      _ =>
        ({
          case JString(date) =>
            ZonedDateTime.parse(date, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          case unknownDate =>
            throw new IllegalArgumentException(
              s"unsupported zoned date time $unknownDate"
            )
        }, {
          case date: ZonedDateTime =>
            JString(date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        })
    )

case object OffsetDateTimeSerializer
    extends CustomSerializer[OffsetDateTime](
      _ =>
        ({
          case JString(date) =>
            OffsetDateTime.parse(date, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          case unknownDate =>
            throw new IllegalArgumentException(
              s"unsupported offset date time $unknownDate"
            )
        }, {
          case date: OffsetDateTime =>
            JString(date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        })
    )

case object LocalDateSerializer
    extends CustomSerializer[LocalDate](
      _ =>
        ({
          case JString(s) => LocalDate.parse(s)
          case unknownDate =>
            throw new IllegalArgumentException(
              s"unsupported local date $unknownDate"
            )

        }, {
          case date: LocalDate => JString(date.toString)
        })
    )

case object SerializedCursorSerializer
    extends CustomSerializer[SerializedCursor](
      _ =>
        ({
          case JString(value) => SerializedCursor(value)
        }, {
          case cursor: SerializedCursor => JString(cursor.value)
        })
    )

case object EmailSerializer
    extends CustomSerializer[Email](
      _ =>
        ({
          case JString(address) => Email(address)
        }, {
          case email: Email => JString(email.address)
        })
    )

/**
  * Conversion from the Circe AST to the Json4s AST.
  *
  * Needed to serialize the `detail` field of the
  * `ChangelogEventDetail.UpdateRecord` case class.
  */
case object CirceJsonSerializer
    extends CustomSerializer[circe.Json](
      _ =>
        ({
          case _ =>
            throw new Exception("Decoding from Json4s to Circe not supported")
        }, {
          case j: circe.Json => Convert.circeToJson4s(j)
        })
    )

object Convert {
  def circeToJson4s(j: circe.Json): JValue = j.fold(
    jsonNull = JNull,
    jsonBoolean = JBool(_),
    jsonNumber = (n: circe.JsonNumber) =>
      // Easy way out: convert everything to decimal/floating point. Don't worry
      // about integers.  This is OK because the serialized representation of a
      // round BigDecimal and equivalent BigInt are the same.
      n.toBigDecimal
        .map(JDecimal(_))
        .getOrElse(JDouble(n.toDouble)),
    jsonString = JString(_),
    jsonArray =
      (v: Vector[circe.Json]) => JArray(v.map(circeToJson4s(_)).toList),
    jsonObject = (o: circe.JsonObject) =>
      JObject(o.toIterable.map {
        case (key, value) => JField(key, circeToJson4s(value))
      }.toList)
  )
}
