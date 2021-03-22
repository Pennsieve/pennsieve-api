// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

import com.blackfynn.api._
import com.blackfynn.helpers.{
  AWSBootstrapHelper,
  BaseBootstrapHelper,
  LocalBootstrapHelper
}
import com.blackfynn.web.{ ResourcesApp, Settings, SwaggerApp }

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import java.io.{ PrintWriter, StringWriter }
import javax.servlet.ServletContext
import org.scalatra.{ LifeCycle, ScalatraServlet }
import org.scalatra.swagger.ApiKey
import scala.concurrent.ExecutionContext
import scalikejdbc.config._

class ScalatraBootstrap extends LifeCycle with LazyLogging {

  implicit val swagger: SwaggerApp = new SwaggerApp
  swagger.addAuthorization(ApiKey("api_key", "query"))

  implicit val system: ActorSystem = ActorSystem("appActorSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  val bootstrapHelper: BaseBootstrapHelper = if (Settings.isLocal) {
    new LocalBootstrapHelper
  } else {
    new AWSBootstrapHelper
  }

  override def init(context: ServletContext) {

    try {

      // initialization
      ///////////////////////////////
      DBsWithEnv("pennsieve.postgres").setupAll()

      // disable CORS support
      ///////////////////////////////
      context.initParameters("org.scalatra.cors.enable") = "false"

      // documentation endpoints
      ///////////////////////////////
      context mount (new ResourcesApp, "/api-docs/*")

      context mount (new ScalatraServlet {
        get("/") {
          "Pennsieve API"
        }
      }, "/*")

      // account endpoints
      ///////////////////////////////
      val accountController =
        new AccountController(bootstrapHelper.insecureContainer, ec)
      context mount (accountController, "/account/*", "account")

      // annotation endpoints
      ///////////////////////////////
      val annotationsController = new AnnotationsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec,
        bootstrapHelper.auditLogger
      )

      context mount (annotationsController, "/annotations/*", "annotations")

      // discussions endpoints
      ///////////////////////////////
      val discussionsController = new DiscussionsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        bootstrapHelper.auditLogger,
        bootstrapHelper.notificationServiceClient,
        ec
      )

      context mount (discussionsController, "/discussions/*", "discussions")

      // api token endpoints
      ///////////////////////////////
      val apiTokenController = new APITokenController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )

      context mount (apiTokenController, "/token/*", "token")

      // general data endpoints
      ///////////////////////////////
      val dataController = new DataController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        materializer,
        ec,
        bootstrapHelper.auditLogger,
        bootstrapHelper.sqsClient
      )
      context mount (dataController, "/data/*", "data")

      // data set endpoints
      //
      ///////////////////////////////

      // NOTE: filters must mount before servlets in the same URL namespace.
      //
      // URLs in a filter must all start with the prefix used here. This is
      // because filters match URLs relative to the root path of the server
      // whereas servlets match URLs relative to the root path MINUS the path
      // prefix defined here.

      val externalPublicationController =
        new ExternalPublicationController(
          bootstrapHelper.insecureContainer,
          bootstrapHelper.secureContainerBuilder,
          bootstrapHelper.doiClient,
          ec
        )
      context mount (externalPublicationController, "/datasets/*", "externalPublications")

      val dataSetsController = new DataSetsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        materializer,
        bootstrapHelper.auditLogger,
        bootstrapHelper.sqsClient,
        bootstrapHelper.modelServiceClient,
        bootstrapHelper.publishClient,
        bootstrapHelper.searchClient,
        bootstrapHelper.doiClient,
        bootstrapHelper.datasetAssetClient,
        bootstrapHelper.insecureContainer.config
          .getInt("pennsieve.max_file_upload_size"),
        ec
      )
      context mount (dataSetsController, "/datasets/*", "datasets")

      val internalDataSetsController = new InternalDataSetsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )
      context mount (internalDataSetsController, "/internal/datasets/*", "internalDatasets")

      // collection endpoints
      ///////////////////////////////
      val collectionsController = new CollectionsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )
      context mount (collectionsController, "/collections/*", "collections")

      // contributor endpoints
      ///////////////////////////////
      val contributorsController = new ContributorsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec,
        bootstrapHelper.orcidClient
      )
      context mount (contributorsController, "/contributors/*", "contributors")

      // file endpoints
      ///////////////////////////////
      val filesController = new FilesController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        materializer,
        bootstrapHelper.auditLogger,
        bootstrapHelper.objectStore,
        bootstrapHelper.modelServiceClient,
        bootstrapHelper.jobSchedulingServiceClient,
        ec
      )

      context mount (filesController, "/files/*", "files")

      // health endpoints
      ///////////////////////////////
      val healthController =
        new HealthController(bootstrapHelper.insecureContainer, ec)

      context mount (healthController, "/health/*", "health")

      // imaging endpoints
      ///////////////////////////////
      val imagingController = new ImagingController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )

      context mount (imagingController, "/imaging/*", "imaging")

      // organization endpoints
      ///////////////////////////////
      val organizationsController = new OrganizationsController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        bootstrapHelper.auditLogger,
        bootstrapHelper.customTermsOfServiceClient,
        bootstrapHelper.cognitoClient,
        ec
      )
      context mount (organizationsController, "/organizations/*", "organizations")

      // onboarding endpoint
      ///////////////////////////////
      val onboardingController = new OnboardingController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )
      context mount (onboardingController, "/onboarding/*", "onboarding")

      // package endpoints
      ///////////////////////////////
      val packagesController = new PackagesController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        bootstrapHelper.auditLogger,
        bootstrapHelper.objectStore,
        bootstrapHelper.jobSchedulingServiceClient,
        bootstrapHelper.urlShortenerClient,
        materializer,
        ec
      )
      context mount (packagesController, "/packages/*", "packages")

      // security endpoints
      ///////////////////////////////
      val securityController = new SecurityController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec
      )
      context mount (securityController, "/security/*", "security")

      // time series endpoints
      ///////////////////////////////
      val timeSeriesController = new TimeSeriesController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        ec,
        materializer
      )
      context mount (timeSeriesController, "/timeseries/*", "timeseries")

      // user endpoints
      ///////////////////////////////
      val userController = new UserController(
        bootstrapHelper.insecureContainer,
        bootstrapHelper.secureContainerBuilder,
        bootstrapHelper.auditLogger,
        bootstrapHelper.authyClient,
        ec,
        bootstrapHelper.orcidClient
      )
      context mount (userController, "/user/*", "user")

    } catch {
      case e: Throwable =>
        val sw = new StringWriter
        e.printStackTrace(new PrintWriter(sw))
        logger.error(sw.toString)
        throw e
    }
  }

  override def destroy(context: ServletContext) {
    system.terminate()
  }
}
