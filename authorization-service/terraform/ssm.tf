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

resource "aws_ssm_parameter" "identity_token_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-identity-pool-id"
  type  = "String"
  value = data.terraform_remote_state.upload_service_v2.outputs.identity_pool_id
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

# The auth service user's password was manually added and then imported into TF state
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

resource "aws_ssm_parameter" "jwt_secret_key" {
  name      = "/${var.environment_name}/${var.service_name}/jwt-secret-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "readme_jwt_key" {
  name      = "/${var.environment_name}/${var.service_name}/readme-jwt-key"
  overwrite = true
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}
