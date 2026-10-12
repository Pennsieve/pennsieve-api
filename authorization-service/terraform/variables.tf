variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

# Service Module Vars

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "ecs_task_iam_role_id" {}
