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
  value = "${var.environment_name}_${replace(var.service_name, "-", "_")}_user"
}

resource "aws_ssm_parameter" "etl_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/etl-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_id
}

resource "aws_ssm_parameter" "job_scheduling_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/job-scheduling-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.job_scheduling_service.outputs.internal_fqdn}"
}

resource "aws_ssm_parameter" "upload_service_host" {
  name  = "/${var.environment_name}/${var.service_name}/upload-service-host"
  type  = "String"
  value = "https://${data.terraform_remote_state.upload_service.outputs.internal_fqdn}"
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

resource "aws_ssm_parameter" "notifications_sqs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/notifications-sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.notifications_queue_id
}

resource "aws_ssm_parameter" "sns_alert_topic" {
  name  = "/${var.environment_name}/${var.service_name}/alert-topic"
  type  = "String"
  value = data.terraform_remote_state.account.outputs.data_management_victor_ops_sns_topic_id
}

resource "aws_ssm_parameter" "sqs_deadletter_queue" {
  name  = "/${var.environment_name}/${var.service_name}/alert-queue"
  type  = "String"
  value = data.terraform_remote_state.etl_infrastructure.outputs.uploads_deadletter_queue_id
}

resource "aws_ssm_parameter" "sqs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.etl_infrastructure.outputs.uploads_queue_id
}

resource "aws_ssm_parameter" "storage_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/storage-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_id
}

resource "aws_ssm_parameter" "uploads_bucket" {
  name  = "/${var.environment_name}/${var.service_name}/uploads-bucket"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_id
}
