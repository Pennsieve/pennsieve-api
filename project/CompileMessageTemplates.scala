package com.pennsieve.templates.compile

import sbt._
import sbt.nio.file.FileTreeView
import sbt.plugins.JvmPlugin
import Keys._
import java.nio.file.Path
import scala.collection.mutable.StringBuilder

/**
  * SBT plugin to read raw files and create Scala functions that
  * string-interpolate the contents of the file.
  *
  * The pluging hooks into the `compile` command and will generate Scala
  * functions whenever the project is compiled.
  */
object CompileMessageTemplates extends AutoPlugin {

  override def requires = JvmPlugin

  // Generate source file on every compilation
  override lazy val projectSettings = Seq(
    Compile / sourceGenerators += compileTemplatesTask.taskValue
  )

  object autoImport {
    val messageTemplatesOutputFile = settingKey[String](
      "name of Scala file for compiled email templates"
    )

    val messageTemplatesOutputPackage = settingKey[String](
      "name of Scala package for compiled email templates"
    )

    val messageTemplatesInputDirectory = settingKey[String](
      "directory of templates to compile"
    )

    val messageTemplatesInputGlob = settingKey[String](
      "glob matching template files"
    )
  }

  import autoImport._

  lazy val compileTemplatesTask = Def.task {

    val outputFile = sourceManaged.value / messageTemplatesOutputFile.value

    val inputGlob = baseDirectory.value.toGlob /
      messageTemplatesInputDirectory.value /
      messageTemplatesInputGlob.value

    val inputFiles: Seq[Path] = FileTreeView
      .default
      .list(inputGlob)
      .filter(_._2.isRegularFile) // exclude directories
      .map(_._1)
      .filterNot(_.getFileName.toString.startsWith(".")) // ignore hidden files

    // Cache the results based on these input files. If the files inputs change,
    // the template is regenerated.
    val cachedFun = FileFunction.cached(
      streams.value.cacheDirectory / "templates"
    ) { (inputFiles: Set[File]) =>

      IO.write(outputFile, s"package ${messageTemplatesOutputPackage.value}\n\n")
      IO.append(outputFile, "object GeneratedMessageTemplates {\n")

      inputFiles.foreach(inputFile => {
        val functionName = deriveTemplateName(inputFile)
        println(s"Generating template $functionName from $inputFile" )

        val template = IO.read(inputFile)
        val function = createTemplateFunction(functionName, template)

        IO.append(outputFile, function)
      })
      IO.append(outputFile, "}")

      Set(outputFile)
    }

   cachedFun(inputFiles.map(_.toFile).toSet).toSeq
  }

  /**
    * Derive a Scala function name from the inputFile name, converting
    * hyphenated names to camelCase
    */
  def deriveTemplateName(inputFile: File): String =
    inputFile
      .toPath
      .getFileName
      .toString
      .split('.') // remove extension
      .headOption
      .getOrElse(throw new Exception(s"Could not derive template name for ${inputFile.toPath}"))
      .split('-')
      .foldLeft("")((base, next) =>
        if (base.isEmpty) next
        else base + next.capitalize
      )

  /**
    * Given a function name "nameTemplate" and a raw file that contains
    *
    *   <h1> ${name} </h1>
    *
    * create a Scala function definition that looks like
    *
    *   def nameTemplate(name: String): String = s"""<h1> ${name} </h1>"""
    */
  def createTemplateFunction(functionName: String, template: String): String = {

    // Find all valid Scala string interpolations of the form ${...} in the template
    val variableNames = "[$$]\\{([a-zA-Z]+)\\}".r
      .findAllMatchIn(template)
      .toList
      .map(_.group(1))
      .toSet
      .toList

    val builder = new StringBuilder()

    builder ++= s"  def $functionName("
    builder ++= variableNames.map(name => s"$name: String").mkString(", ")
    builder ++= "): String = s\"\"\""
    builder ++= template
    builder ++= "\"\"\"\n\n"

    builder.toString
  }
}
