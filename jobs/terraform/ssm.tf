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

resource "aws_ssm_parameter" "jwt_secret_key" {
  name      = "/${var.environment_name}/${var.service_name}/jwt-secret-key"
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

resource "aws_ssm_parameter" "parallelism" {
  name  = "/${var.environment_name}/${var.service_name}/parallelism"
  type  = "String"
  value = var.parallelism
}

resource "aws_ssm_parameter" "gateway_internal_host" {
  name  = "/${var.environment_name}/${var.service_name}/gateway-internal-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.gateway.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "s3_endpoint" {
  name  = "/${var.environment_name}/${var.service_name}/s3-endpoint"
  type  = "String"
  value = var.s3_endpoint
}

resource "aws_ssm_parameter" "s3_storage_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/s3-storage-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_id
}

resource "aws_ssm_parameter" "s3_dataset_asset_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/s3-dataset-asset-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_id
}


resource "aws_ssm_parameter" "sqs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_id
}

resource "aws_ssm_parameter" "time_series_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/time-series-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.timeseries_bucket_id
}

// Get SNS topic for integration events for changelog
resource "aws_ssm_parameter" "integration_sns_topic" {
  name  = "/${var.environment_name}/${var.service_name}/integration-events-sns-topic"
  type  = "String"
  value = data.terraform_remote_state.integration_service.outputs.sns_topic_arn
}
