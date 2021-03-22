package com.blackfynn

package object clients {
  final case class ServerError(message: String) extends Exception {
    override def getMessage: String = message
  }
}
