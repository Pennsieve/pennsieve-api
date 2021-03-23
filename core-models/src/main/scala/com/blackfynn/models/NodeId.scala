// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import cats.implicits._

import io.circe.syntax._
import io.circe.{ Decoder, Encoder }

import java.util.UUID

case class NodeId(prefix: String, nodeCode: String, uuid: UUID) {
  override def toString = NodeId.toString(this)
}

object NodeId {
  implicit val nodeIdEncoder: Encoder[NodeId] =
    Encoder.instance(nid => NodeId.toString(nid).asJson)
  implicit val nodeIdDecoder: Decoder[NodeId] = Decoder.decodeString.emap {
    nid =>
      NodeId.fromString(nid).leftMap(_.getMessage)
  }

  def toString(nid: NodeId) =
    s"${nid.prefix}:${nid.nodeCode}:${nid.uuid.toString}"
  def fromString(nodeId: String): Either[Throwable, NodeId] = {
    val parts = nodeId.split(":").toList
    Either
      .catchNonFatal {
        val prefix = parts(0)
        val code = parts(1)
        val idString = parts(2)
        val uuid = UUID.fromString(idString)
        NodeId(prefix, code, uuid)
      }
      .leftMap(_ => new Throwable(s"problem parsing user nodeid: $nodeId"))
  }

  def generateForCode(nodeCode: String): NodeId =
    NodeId("N", nodeCode, UUID.randomUUID())
}

object NodeCodes {
  val userCode = "user"
  val organizationCode = "organization"
  val teamCode = "team"
  val fileCode = "file"
  val packageCode = "package"
  val collectionCode = "collection"
  val dataSetCode = "dataset"
  val channelCode = "channel"

  val packageCodes = Set(packageCode, collectionCode, dataSetCode)

  def nodeIdPrefix(code: String): String = s"N:$code:"
  def nodeIdIsA(id: String, code: String): Boolean =
    id.startsWith(nodeIdPrefix(code))
  def nodeIdIsOneOf(codes: Set[String], id: String): Boolean =
    codes.contains(extractNodeCodeFromId(id))
  def extractNodeCodeFromId(id: String): String = id.split(":")(1)
  def extractUUIDFromId(id: String): String = id.split(":")(2)
  def uuidToNodeId(uuid: String, code: String): String =
    nodeIdPrefix(code) + uuid
  def generateId(code: String): String =
    uuidToNodeId(UUID.randomUUID.toString, code)
}
