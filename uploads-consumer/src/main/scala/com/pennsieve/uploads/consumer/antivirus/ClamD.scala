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

package com.pennsieve.uploads.consumer.antivirus

import cats.data._
import cats.kernel.Eq

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.net.Socket

import scala.collection.immutable.Stream
import scala.io.Source

sealed trait ScanResult
case object Clean extends ScanResult
case object Infected extends ScanResult
case object AlreadyMoved extends ScanResult
case object Locked extends ScanResult
case object Scanning extends ScanResult

class SizeLimitExceededException(response: String) extends Exception {
  final override def getMessage: String =
    s"clamd size limit exceeded with reply: $response"
}

class ClamD(host: String, port: Int) {

  final val CHUNK_SIZE: Int = 2048
  final val INSTREAM_HANDSHAKE: Array[Byte] =
    "zINSTREAM\u0000".getBytes(StandardCharsets.US_ASCII)
  final val SIZE_LIMIT_RESPONSE_MATCH: String = "SIZE LIMIT"

  def scan(input: InputStream): ScanResult = {
    // socket connection to clamd server
    val socket: Socket = new Socket(host, port)

    try {
      // output stream for streaming input stream contents to clamd server
      val output: OutputStream = new BufferedOutputStream(
        socket.getOutputStream()
      )

      // begin clamd INSTREAM request
      output.write(INSTREAM_HANDSHAKE)
      output.flush()

      // input stream for clamd server response
      val responseStream: InputStream = socket.getInputStream()

      val buffer: Array[Byte] = new Array[Byte](CHUNK_SIZE)

      LazyList.continually(input.read(buffer)).takeWhile(_ != -1).foreach {
        length =>
          // stream a CHUNK_SIZE worth of contents to clamd
          val outputBuffer: Array[Byte] =
            ByteBuffer.allocate(4).putInt(length).array()
          output.write(outputBuffer)
          output.write(buffer, 0, length)

          // if there is a response from the clamd server before the stream
          // finishes there is an issue with the clamd server
          if (responseStream.available() > 0) {
            val response: String = Source
              .fromInputStream(responseStream)
              .getLines()
              .mkString
              .toUpperCase

            if (response.contains(SIZE_LIMIT_RESPONSE_MATCH))
              throw new SizeLimitExceededException(response)
            else
              throw new IOException(
                "Anti-virus scan aborted. Reply from server: " + response
              )
          }
      }

      // terminate clamd INSTREAM request
      output.write(Array[Byte](0, 0, 0, 0))
      output.flush()

      // final response from clamd INSTREAM request
      val response =
        Source.fromInputStream(responseStream).getLines().mkString.toUpperCase

      if (response.contains(SIZE_LIMIT_RESPONSE_MATCH))
        throw new SizeLimitExceededException(response)

      if (response.contains("OK") && !response.contains("FOUND"))
        Clean
      else
        Infected

    } finally {
      socket.close()
    }
  }

}
