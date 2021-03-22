package com.blackfynn.akka.http

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.http.scaladsl.server.directives.ExecutionDirectives.handleRejections
import com.github.swagger.akka.model.Info
import com.github.swagger.akka.SwaggerHttpService
import io.swagger.models.Scheme
import io.swagger.models.auth.ApiKeyAuthDefinition
import io.swagger.models.auth.In.HEADER
import net.ceedubs.ficus.Ficus._

class SwaggerDocService(
  override val host: String,
  override val schemes: List[Scheme],
  override val apiClasses: Set[Class[_]],
  override val unwantedDefinitions: Seq[String]
) extends SwaggerHttpService
    with RouteService {

  override val basePath = "/" // the basePath for the API you are exposing
  override val apiDocsPath = "api-docs" // where you want the swagger-json endpoint exposed
  override val info = Info() // provides license and other description details
  override val securitySchemeDefinitions = Map(
    "Bearer" -> new ApiKeyAuthDefinition("Authorization", HEADER)
  )
}
