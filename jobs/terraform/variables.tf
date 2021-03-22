variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

# Jobs Service
variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "data_postgres_database" {
  default = "data_postgres"
}

variable "ecs_task_iam_role_id" {}

variable "parallelism" {
  default = "10"
}

variable "s3_endpoint" {
  default = "https://s3.amazonaws.com"
}
