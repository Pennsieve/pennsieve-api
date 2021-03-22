// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import io.circe.Json
import java.io.{ File => JavaFile, FileWriter }

object FileOutputWriter {
  def writeJson(json: Json, output: JavaFile): Unit = {
    val parentDirectory = output.getParentFile()

    if (parentDirectory != null && !parentDirectory.exists()) {
      parentDirectory.mkdirs()
    }

    val fileWriter = new FileWriter(output)

    fileWriter.write(json.noSpaces)
    fileWriter.close()
  }
}
