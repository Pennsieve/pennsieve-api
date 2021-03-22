variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

# Service Module Vars

variable "authy_api_url" {}

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "ecs_task_iam_role_id" {}

# New Relic
variable "newrelic_agent_enabled" {
  default = "true"
}
locals {
  java_opts = [
    "-javaagent:/app/newrelic.jar",
    "-Dnewrelic.config.agent_enabled=${var.newrelic_agent_enabled}",
    "-Dnewrelic.config.distributed_tracing.enabled=true",
  ]

  service = element(split("-", var.service_name), 0)
  tier    = element(split("-", var.service_name), 1)
}