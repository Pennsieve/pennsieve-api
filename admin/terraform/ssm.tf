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

resource "aws_ssm_parameter" "pennsieve_postgres_database" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-database"
  type  = "String"
  value = var.pennsieve_postgres_database
}

resource "aws_ssm_parameter" "pennsieve_postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-host"
  type  = "String"
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

resource "aws_ssm_parameter" "data_postgres_connection" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-connection"
  type  = "String"
  value = "jdbc:postgresql://${aws_ssm_parameter.data_postgres_host.value}:${aws_ssm_parameter.data_postgres_port.value}/${var.data_postgres_database}"
}

resource "aws_ssm_parameter" "data_postgres_database" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-database"
  type  = "String"
  value = var.data_postgres_database
}

resource "aws_ssm_parameter" "data_postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}/data-postgres-host"
  type  = "String"
  value = data.terraform_remote_state.data_postgres.outputs.master_fqdn
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
  value = data.terraform_remote_state.data_postgres.outputs.master_port
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

resource "aws_ssm_parameter" "etl_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/etl-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.job_scheduling_service.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "etl_organization_quota" {
  name  = "/${var.environment_name}/${var.service_name}/etl-organization-quota"
  type  = "String"
  value = var.etl_organization_quota
}

resource "aws_ssm_parameter" "jobs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_id
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

resource "aws_ssm_parameter" "root_account_arn" {
  name  = "/${var.environment_name}/${var.service_name}/root-account-arn"
  type  = "String"
  value = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
}

resource "aws_ssm_parameter" "s3_region" {
  name  = "/${var.environment_name}/${var.service_name}/s3-region"
  type  = "String"
  value = data.aws_region.current_region.name
}

resource "aws_ssm_parameter" "s3_storage_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/storage-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_id
}

resource "aws_ssm_parameter" "s3_terms_of_service_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/terms-of-service-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.terms_of_service_bucket_id
}
