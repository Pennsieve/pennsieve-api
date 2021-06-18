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

package com.pennsieve.traits

import java.sql.{ PreparedStatement, ResultSet, Timestamp }
import java.time.{ ZoneOffset, ZonedDateTime }

import cats.implicits._
import com.pennsieve.models._
import com.pennsieve.timeseries.AnnotationData
import com.pennsieve.utilities.AbstractError
import com.github.tminglei.slickpg._
import com.github.tminglei.slickpg.agg.PgAggFuncSupport
import io.circe.Json
import io.circe.syntax._
import slick.ast.Library.SqlAggregateFunction
import slick.ast.{ BaseTypedType, TypedType }
import slick.jdbc.{
  GetResult,
  JdbcType,
  PositionedParameters,
  SQLActionBuilder,
  SetParameter
}
import slick.lifted.FunctionSymbolExtensionMethods._

import java.time.{ LocalDate, OffsetDateTime, ZoneOffset, ZonedDateTime }
import java.time.format.DateTimeFormatter
import java.util.UUID

trait PostgresProfile
    extends ExPostgresProfile
    with PgArraySupport
    with PgAggFuncSupport
    with PgDate2Support
    with PgCirceJsonSupport
    with PgRangeSupport
    with PgSearchSupport {

  override val pgjson = "jsonb" // jsonb support is in postgres 9.4.0 onward; for 9.3.x use "json"

  trait Implicits {
    self: API with CirceImplicits =>

    implicit val fileTypeMapper = MappedColumnType
      .base[FileType, String](s => s.entryName, s => FileType.withName(s))

    implicit val PublishStatusMapper =
      MappedColumnType.base[PublishStatus, String](
        s => s.entryName,
        s => PublishStatus.withName(s)
      )

    implicit val RelationshipTypeMapper =
      MappedColumnType.base[RelationshipType, String](
        s => s.entryName,
        s => RelationshipType.withName(s)
      )

    implicit val FileStateMapper =
      MappedColumnType
        .base[FileState, String](s => s.entryName, s => FileState.withName(s))

    implicit val DegreeMapper =
      MappedColumnType
        .base[Degree, String](s => s.entryName, s => Degree.withName(s))

    implicit val publicationStatusMapper =
      MappedColumnType.base[PublicationStatus, String](
        s => s.entryName,
        s => PublicationStatus.withName(s)
      )

    implicit val systemTeamTypeMapper =
      MappedColumnType.base[SystemTeamType, String](
        s => s.entryName,
        s => SystemTeamType.withName(s)
      )

    implicit val publicationTypeMapper =
      MappedColumnType.base[PublicationType, String](
        s => s.entryName,
        s => PublicationType.withName(s)
      )

    implicit val fileObjectTypeMapper =
      MappedColumnType.base[FileObjectType, String](
        s => s.entryName,
        s => FileObjectType.withName(s)
      )

    implicit val onboardingEventTypeMapper =
      MappedColumnType.base[OnboardingEventType, String](
        e => e.entryName,
        e => OnboardingEventType.withName(e)
      )

    implicit val packageTypeMapper = MappedColumnType
      .base[PackageType, String](t => t.entryName, s => PackageType.withName(s))

    implicit val packageStateMapper =
      MappedColumnType.base[PackageState, String](
        s => s.entryName,
        s => PackageState.withName(s)
      )

    implicit val roleMapper =
      MappedColumnType
        .base[Role, String](r => r.entryName, r => Role.withName(r))

    implicit val permissionBitMapper = MappedColumnType.base[DBPermission, Int](
      bitMap => bitMap.value,
      bitMap => DBPermission.withValue(bitMap)
    )

    implicit val featureMapper = MappedColumnType
      .base[Feature, String](f => f.entryName, s => Feature.withName(s))

    implicit val subscriptionStatusMapper =
      MappedColumnType.base[SubscriptionStatus, String](
        s => s.entryName,
        s => SubscriptionStatus.withName(s)
      )

    implicit val fileProcessingState =
      MappedColumnType.base[FileProcessingState, String](
        s => s.entryName,
        s => FileProcessingState.withName(s)
      )

    implicit val countHashMapMapper =
      MappedColumnType.base[Map[String, Long], Json](
        counts => counts.asJson,
        json => json.as[Map[String, Long]].right.get
      )

    implicit val packagePropertyMapper =
      MappedColumnType.base[List[ModelProperty], Json](
        properties => properties.asJson,
        json => json.as[List[ModelProperty]].right.get
      )

    implicit val annotationPathElementMapper =
      MappedColumnType.base[List[PathElement], Json](
        elements => elements.asJson,
        json => json.as[List[PathElement]].right.get
      )

    implicit val annotationDataMapper =
      MappedColumnType.base[AnnotationData, Json](
        annotationData => annotationData.asJson,
        json =>
          json.asObject match {
            // using nulls here since this should be wrapped in a Option on the column definition type ex: Option[AnnotationData]
            case Some(obj) if obj.keys.isEmpty => null
            case None => null
            case _ =>
              json
                .as[AnnotationData]
                .orElse(
                  Either
                    .catchNonFatal(AnnotationData.deserialize(json.noSpaces))
                )
                .valueOr(
                  err =>
                    throw new RuntimeException(s"Unable to parse $json: $err")
                )
          }
      )

    implicit val stringListTypeMapper =
      new SimpleArrayJdbcType[String]("text").to(_.toList)

    implicit val datasetStateMapper =
      MappedColumnType.base[DatasetState, String](
        s => s.entryName,
        s => DatasetState.withName(s)
      )

    implicit val datasetTypeMapper = MappedColumnType
      .base[DatasetType, String](_.entryName, DatasetType.withName)

    implicit val embargoAccessMapper = MappedColumnType
      .base[EmbargoAccess, String](_.entryName, EmbargoAccess.withName)

    implicit val dimensionAssignmentMapper =
      MappedColumnType.base[DimensionAssignment, String](
        s => s.entryName,
        s => DimensionAssignment.withName(s)
      )

    implicit val orcidAuthorizationMapper =
      MappedColumnType.base[OrcidAuthorization, Json](
        orcidAuthorization => orcidAuthorization.asJson,
        jsonString => jsonString.as[OrcidAuthorization].right.get
      )

    implicit val datasetStatusMapper =
      MappedColumnType.base[DefaultDatasetStatus, String](
        s => s.entryName,
        s => DefaultDatasetStatus.withName(s)
      )

    implicit val datasetStatusUsageMapper =
      MappedColumnType.base[DatasetStatusInUse, Boolean](
        s => s.value,
        s => DatasetStatusInUse(s)
      )

    implicit val fileChecksumMapper =
      MappedColumnType.base[FileChecksum, Json](
        fileChecksum => fileChecksum.asJson,
        jsonString => jsonString.as[FileChecksum].right.get
      )

    implicit val licenseMapper: JdbcType[License] with BaseTypedType[License] =
      MappedColumnType
        .base[License, String](s => s.entryName, s => License.withName(s))

    implicit val etagMapper =
      MappedColumnType.base[ETag, ZonedDateTime](
        etag => etag.datetime,
        datetime => ETag(datetime)
      )

    implicit val doiMapper =
      MappedColumnType
        .base[Doi, String](doi => doi.value, string => Doi(string))

    implicit val changelogEventNameMapper =
      MappedColumnType
        .base[ChangelogEventName, String](
          s => s.entryName,
          s => ChangelogEventName.withName(s)
        )

    implicit val cognitoUserPoolIdMapper =
      MappedColumnType
        .base[CognitoId.UserPoolId, UUID](
          id => id.value,
          uuid => CognitoId.UserPoolId(uuid)
        )

    implicit val cognitoTokenPoolIdMapper =
      MappedColumnType
        .base[CognitoId.TokenPoolId, UUID](
          id => id.value,
          uuid => CognitoId.TokenPoolId(uuid)
        )

    // https://github.com/tminglei/slick-pg/issues/289
    // Declare the name of an aggregate function:
    val ArrayAgg = new SqlAggregateFunction("array_agg")

    // Implement the aggregate function as an extension method:
    implicit class ArrayAggColumnQueryExtensionMethods[P, C[_]](
      val q: Query[Rep[P], _, C]
    ) {
      def arrayAgg[B](implicit tm: TypedType[List[B]]) =
        ArrayAgg.column[List[B]](q.toNode)
    }

    /**
      * Compose multiple raw `sql` statements using `++` and `.opt`, merging both
      * the query string and the query parameters into a new SQLActionBuilder.
      *
      * From https://github.com/slick/slick/issues/1161#issuecomment-481743802
      */
    implicit class SlickPlainSqlActionBuilderSyntax(a: SQLActionBuilder) {
      def ++(b: SQLActionBuilder): SQLActionBuilder = concat(a, b)

      def opt[T](o: Option[T])(f: T => SQLActionBuilder): SQLActionBuilder =
        o.fold(a)(f andThen ++)

      private def concat(
        a: SQLActionBuilder,
        b: SQLActionBuilder
      ): SQLActionBuilder = {
        SQLActionBuilder(
          a.queryParts ++ Seq(" ") ++ b.queryParts,
          new SetParameter[Unit] {
            def apply(p: Unit, pp: PositionedParameters): Unit = {
              a.unitPConv.apply(p, pp)
              b.unitPConv.apply(p, pp)
            }
          }
        )
      }
    }
  }

  /**
    * Store a `ZonedDateTime` object as a Postgres `TIMESTAMP`. For example:
    *
    *     2019-11-28 10:17:50.516884
    *
    * We assume that every `TIMESTAMP` in the database is UTC time.
    *
    * This override is required because Slick 3.3.0 added support for
    * `java.time` columns [1] and broke our previous mapping between `TIMESTAMP`
    * and `ZonedDateTime`.
    *
    * See [2] for Slick's original `ZonedDateTimeJdbcType` implementation.
    *
    * [1] https://scala-slick.org/doc/3.3.0/upgrade.html#support-for-java.time-columns
    * [2] https://github.com/slick/slick/blob/16545a6954960e39c186a9ad146235cee4e4bb48/slick/src/main/scala/slick/jdbc/JdbcTypesComponent.scala#L338-L365
    */
  override val columnTypes = new JdbcTypes {

    override val zonedDateType = new ZonedDateTimeJdbcType {

      override def sqlType: Int = java.sql.Types.TIMESTAMP

      override def setValue(
        z: ZonedDateTime,
        p: PreparedStatement,
        idx: Int
      ): Unit =
        p.setTimestamp(idx, Timestamp.from(z.toInstant))

      override def getValue(r: ResultSet, idx: Int): ZonedDateTime =
        r.getTimestamp(idx) match {
          case null => null
          case t =>
            ZonedDateTime.ofInstant(t.toInstant, ZoneOffset.UTC)
        }

      override def updateValue(z: ZonedDateTime, r: ResultSet, idx: Int) =
        r.updateTimestamp(idx, Timestamp.from(z.toInstant))

      override def valueToSQLLiteral(z: ZonedDateTime) =
        s"'${Timestamp.from(z.toInstant).toString}'"
    }
  }

  object PennsieveAPI
      extends API
      with SimpleArrayImplicits
      with SimpleArrayPlainImplicits
      with ArrayImplicits
      with CirceImplicits
      with CirceJsonPlainImplicits
      with RangeImplicits
      with SearchImplicits
      with SearchAssistants
      with Implicits {

    def assert[T](predicate: => Boolean)(error: => AbstractError): DBIO[Unit] =
      if (predicate) DBIO.successful(())
      else DBIO.failed(error)

    // Postgres functions
    object F {
      val arrayToString =
        SimpleFunction.binary[List[String], String, String]("array_to_string")

      def regexpReplace(
        source: Rep[String],
        pattern: Rep[String],
        replacement: Rep[String],
        flags: Rep[String]
      ) =
        SimpleFunction[String]("regexp_replace")
          .apply(Seq(source, pattern, replacement, flags))
    }

    implicit val setZonedDateTimeParameter: SetParameter[ZonedDateTime] =
      new SetParameter[ZonedDateTime] {
        def apply(z: ZonedDateTime, pp: PositionedParameters) =
          pp.setTimestamp(Timestamp.from(z.toInstant))
      }

    implicit val getZonedDateTime: GetResult[ZonedDateTime] =
      GetResult { p =>
        ZonedDateTime.ofInstant(p.<<[Timestamp].toInstant, ZoneOffset.UTC)
      }

    implicit val setLocalDateParameter: SetParameter[LocalDate] =
      new SetParameter[LocalDate] {
        def apply(d: LocalDate, pp: PositionedParameters) =
          pp.setObject(
            d.format(DateTimeFormatter.ISO_LOCAL_DATE),
            java.sql.Types.DATE
          )
      }

  }

  override val api = PennsieveAPI
}

object PostgresProfile extends PostgresProfile
