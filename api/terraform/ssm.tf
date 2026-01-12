resource "aws_ssm_parameter" "bitly_access_token" {
  name      = "/${var.environment_name}/${var.service_name}/bitly-access-token"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "cognito_user_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-user-pool-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.user_pool_2_id
}

resource "aws_ssm_parameter" "cognito_user_pool_app_client_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-user-pool-app-client-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.user_pool_2_client_id
}

resource "aws_ssm_parameter" "cognito_token_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-token-pool-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.token_pool_id
}

resource "aws_ssm_parameter" "cognito_token_pool_app_client_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-token-pool-app-client-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.token_pool_client_id
}

resource "aws_ssm_parameter" "pennsieve_api_host" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-api-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.gateway.outputs.private_fqdn}"
}

resource "aws_ssm_parameter" "pennsieve_app_host" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-app-host"
  type  = "String"
  value = "https://app.${data.terraform_remote_state.account.outputs.domain_name}"
}

# THIS SHOULD BE POINTING TO THE HEROKU REDIRECT
resource "aws_ssm_parameter" "pennsieve_discover_app" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-discover-app"
  type  = "SecureString"
  value = data.terraform_remote_state.discover_service.outputs.discover_route53_record
}

resource "aws_ssm_parameter" "pennsieve_environment" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-environment"
  type  = "String"
  value = var.pennsieve_environment
}

resource "aws_ssm_parameter" "catalina_opts" {
  name  = "/${var.environment_name}/${var.service_name}/catalina-opts"
  type  = "String"
  value = "-Xms${var.initial_heap_size} -Xmx${var.max_heap_size} -XX:MaxPermSize=${var.max_perm_size}"
}

resource "aws_ssm_parameter" "data_postgres_database" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-database"
  type  = "String"
  value = var.data_postgres_database
}

resource "aws_ssm_parameter" "data_postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-host"
  type  = "String"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_fqdn
}

resource "aws_ssm_parameter" "data_postgres_password" {
  name      = "/${var.environment_name}/${var.service_name}/data-postgres-password"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "data_postgres_port" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-port"
  type  = "String"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_port
}

resource "aws_ssm_parameter" "data_postgres_user" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-user"
  type  = "String"
  value = "${var.environment_name}_${var.service_name}_user"
}

resource "aws_ssm_parameter" "discover_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/discover-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.discover_service.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "gateway_internal_host" {
  name  = "/${var.environment_name}/${var.service_name}/gateway-internal-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.gateway.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "doi_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/doi-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.doi_service.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "email_host" {
  name  = "/${var.environment_name}/${var.service_name}/email-host"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.app_route53_record
}

resource "aws_ssm_parameter" "email_support_email" {
  name  = "/${var.environment_name}/${var.service_name}/email-support-email"
  type  = "String"
  value = "support@${data.terraform_remote_state.account.outputs.domain_name}"
}

resource "aws_ssm_parameter" "java_opts" {
  name  = "/${var.environment_name}/${var.service_name}/java-opts"
  type  = "String"
  value = join(" ", local.java_opts)
}

resource "aws_ssm_parameter" "job_scheduling_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/job-scheduling-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.job_scheduling_service.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "job_scheduling_service_queue_size" {
  name  = "/${var.environment_name}/${var.service_name}/job-scheduling-service-queue-size"
  type  = "String"
  value = var.job_scheduling_service_queue_size
}

resource "aws_ssm_parameter" "job_scheduling_service_rate_limit" {
  name  = "/${var.environment_name}/${var.service_name}/job-scheduling-service-rate-limit"
  type  = "String"
  value = var.job_scheduling_service_rate_limit
}

resource "aws_ssm_parameter" "jwt_secret_key" {
  name      = "/${var.environment_name}/${var.service_name}/jwt-secret-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "new_relic_app_name" {
  name  = "/${var.environment_name}/${var.service_name}/new-relic-app-name"
  type  = "String"
  value = "${var.environment_name}-${var.service_name}-${var.tier}"
}

resource "aws_ssm_parameter" "new_relic_labels" {
  name  = "/${var.environment_name}/${var.service_name}/new-relic-labels"
  type  = "String"
  value = "Environment:${var.environment_name};Service:${var.service_name};Tier:${var.tier}"
}

resource "aws_ssm_parameter" "new_relic_license_key" {
  name      = "/${var.environment_name}/${var.service_name}/new-relic-license-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "orcid_client_id" {
  name  = "/${var.environment_name}/${var.service_name}/orcid-client-id"
  type  = "String"
  value = var.orcid_client_id
}

resource "aws_ssm_parameter" "orcid_client_secret" {
  name      = "/${var.environment_name}/${var.service_name}/orcid-client-secret"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "orcid_token_url" {
  name  = "/${var.environment_name}/${var.service_name}/orcid-token-url"
  type  = "String"
  value = var.orcid_token_url
}

resource "aws_ssm_parameter" "orcid_redirect_url" {
  name  = "/${var.environment_name}/${var.service_name}/orcid-redirect-url"
  type  = "String"
  value = "https://${data.terraform_remote_state.platform_infrastructure.outputs.app_route53_record}/orcid-redirect"
}

resource "aws_ssm_parameter" "orcid_read_public_token" {
  name      = "/${var.environment_name}/${var.service_name}/orcid-read-public-token"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "orcid_get_record_base_url" {
  name  = "/${var.environment_name}/${var.service_name}/orcid-get-record-base-url"
  type  = "String"
  value = var.orcid_get_record_base_url
}

resource "aws_ssm_parameter" "orcid_update_profile_base_url" {
  name  = "/${var.environment_name}/${var.service_name}/orcid-update-profile-base-url"
  type  = "String"
  value = var.orcid_update_profile_base_url
}

# BF Postgres
resource "aws_ssm_parameter" "pennsieve_postgres_database" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-database"
  type  = "SecureString"
  value = var.pennsieve_postgres_database
}

resource "aws_ssm_parameter" "pennsieve_postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-host"
  type  = "SecureString"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_fqdn
}

resource "aws_ssm_parameter" "pennsieve_postgres_password" {
  name      = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-password"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "pennsieve_postgres_port" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-port"
  type  = "String"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_port
}

resource "aws_ssm_parameter" "pennsieve_postgres_user" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-user"
  type  = "String"
  value = "${var.environment_name}_${var.service_name}_user"
}

# $env-jobs-queue
resource "aws_ssm_parameter" "sqs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_id
}
resource "aws_ssm_parameter" "sqs_queue_v2" {
  name  = "/${var.environment_name}/${var.service_name}/sqs-queue-v2"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_v2_id
}

# $env-etl-uploads-queue
resource "aws_ssm_parameter" "uploads_queue" {
  name  = "/${var.environment_name}/${var.service_name}/uploads-queue"
  type  = "String"
  value = data.terraform_remote_state.etl_infrastructure.outputs.uploads_queue_name
}

# S3
resource "aws_ssm_parameter" "storage_bucket_name" {
  name  = "/${var.environment_name}/${var.service_name}/storage-bucket-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_id
}

resource "aws_ssm_parameter" "terms_of_service_bucket_name" {
  name  = "/${var.environment_name}/${var.service_name}/terms-of-service-bucket-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.terms_of_service_bucket_id
}

resource "aws_ssm_parameter" "uploads_bucket_name" {
  name  = "/${var.environment_name}/${var.service_name}/uploads-bucket-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_id
}

resource "aws_ssm_parameter" "workflow_bucket_name" {
  name  = "/${var.environment_name}/${var.service_name}/workflow-bucket-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_id
}

resource "aws_ssm_parameter" "dataset_assets_bucket_name" {
  name  = "/${var.environment_name}/${var.service_name}/dataset-assets-bucket-name"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_id
}

# Pass in role the service is using for now
resource "aws_ssm_parameter" "pennsieve_s3_uploader_role" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-s3-uploader-role"
  type  = "String"
  value = aws_iam_role.uploader_iam_role.arn
}

resource "aws_ssm_parameter" "recaptcha_site_key" {
  name  = "/${var.environment_name}/${var.service_name}/recaptcha-site-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "recaptcha_secret_key" {
  name  = "/${var.environment_name}/${var.service_name}/recaptcha-secret-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

// Get SNS topic for integration events for changelog
resource "aws_ssm_parameter" "integration_sns_topic" {
  name  = "/${var.environment_name}/${var.service_name}/integration-events-sns-topic"
  type  = "String"
  value = data.terraform_remote_state.integration_service.outputs.sns_topic_arn
}

resource "aws_ssm_parameter" "identity_token_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-identity-pool-id"
  type  = "String"
  value = data.terraform_remote_state.upload_service_v2.outputs.identity_pool_id
}

resource "aws_ssm_parameter" "publishing_default_workflow" {
  name  = "/${var.environment_name}/${var.service_name}/publishing-default-workflow"
  type  = "String"
  value = var.publishing_default_workflow
}

# ECS Delete Task Configuration
resource "aws_ssm_parameter" "ecs_delete_task_enabled" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-enabled"
  type  = "String"
  value = var.ecs_delete_task_enabled
}

resource "aws_ssm_parameter" "ecs_delete_task_cluster" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-cluster"
  type  = "String"
  value = data.terraform_remote_state.ecs_cluster.outputs.cluster_arn
}

resource "aws_ssm_parameter" "ecs_delete_task_definition" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-definition"
  type  = "String"
  value = aws_ecs_task_definition.delete_task.arn
}

resource "aws_ssm_parameter" "ecs_delete_task_subnets" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-subnets"
  type  = "String"
  value = join(",", data.terraform_remote_state.vpc.outputs.private_subnet_ids)
}

resource "aws_ssm_parameter" "ecs_delete_task_security_group" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-security-group"
  type  = "String"
  value = var.ecs_delete_task_security_group
}

resource "aws_ssm_parameter" "ecs_delete_task_container_name" {
  name  = "/${var.environment_name}/${var.service_name}/ecs-delete-task-container-name"
  type  = "String"
  value = var.ecs_delete_task_container_name
}
