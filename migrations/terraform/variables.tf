variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "pennsieve_postgres_user" {}

variable "pennsieve_postgres_use_ssl" {
  default = "true"
}

variable "data_postgres_database" {
  default = "data_postgres"
}

variable "data_postgres_user" {}

variable "data_postgres_use_ssl" {
  default = "true"
}