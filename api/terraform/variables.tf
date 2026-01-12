variable "aws_account" {}

variable "ecs_task_iam_role_arn" {}

variable "ecs_task_iam_role_id" {}

variable "environment_name" {}

variable "service_name" {}

variable "tier" {
  default = "service"
}

variable "vpc_name" {}

# API Parameters

variable "analytics_service_queue_size" {
  default = "10000"
}

variable "analytics_service_rate_limit" {
  default = "100"
}

variable "pennsieve_environment" {}

variable "data_postgres_database" {
  default = "data_postgres"
}

variable "job_scheduling_service_queue_size" {
  default = "10000"
}

variable "job_scheduling_service_rate_limit" {
  default = "100"
}

variable "orcid_client_id" {}

variable "orcid_token_url" {}

variable "orcid_get_record_base_url" {}

variable "orcid_update_profile_base_url" {}

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

# Catalina Opts

variable "initial_heap_size" {
  default = "4096M"
}

variable "max_heap_size" {
  default = "4096M"
}

variable "max_perm_size" {
  default = "256M"
}

# Java Opts

variable "newrelic_agent_enabled" {
  default = "true"
}

variable "publishing_default_workflow" {
  default = "5"
}

# ECS Delete Task Configuration
variable "ecs_delete_task_enabled" {
  default = "false"
}

variable "ecs_delete_task_definition" {
  default = ""
}

variable "ecs_delete_task_security_group" {
  default = ""
}

variable "ecs_delete_task_container_name" {
  default = "delete-task"
}

locals {
  java_opts = [
    "-javaagent:/usr/local/tomcat/newrelic/newrelic.jar",
    "-Dnewrelic.config.agent_enabled=${var.newrelic_agent_enabled}",
  ]
}
