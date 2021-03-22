// POSTGRES CONFIGURATION
resource "aws_ssm_parameter" "postgres_user" {
  name  = "/${var.environment_name}/${var.service_name}-${var.tier}/local-postgres-user"
  type  = "String"
  value = "${var.environment_name}_${var.service_name}_${var.tier}_user"
}

resource "aws_ssm_parameter" "postgres_password" {
  name      = "/${var.environment_name}/${var.service_name}-${var.tier}/local-postgres-password"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}-${var.tier}/local-postgres-host"
  type  = "String"
  value = var.postgres_host
}

resource "aws_ssm_parameter" "postgres_port" {
  name  = "/${var.environment_name}/${var.service_name}-${var.tier}/local-postgres-port"
  type  = "String"
  value = var.postgres_port
}

resource "aws_ssm_parameter" "postgres_db" {
  name  = "/${var.environment_name}/${var.service_name}-${var.tier}/local-postgres-db"
  type  = "String"
  value = var.postgres_db
}
