variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

# Admin Service Parameters

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "data_postgres_database" {
  default = "data_postgres"
}

variable "ecs_task_iam_role_id" {}

variable "etl_organization_quota" {}

locals {
  resource_prefix = "${var.environment_name}-${var.service_name}"

  common_tags = {
    aws_account      = var.aws_account
    aws_region       = data.aws_region.current_region.name
    environment_name = var.environment_name
    service_name     = var.service_name
  }
}
