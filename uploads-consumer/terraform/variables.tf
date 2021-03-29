variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

variable "mount_points" {
  default = ""
}

variable "image_tag" {}

# App Autoscalling

variable "appautoscaling_target_max_capacity" {
  default = 4
}

variable "appautoscaling_target_min_capacity" {
  default = 1
}

variable "appautoscaling_scale_in_cooldown" {
  default = 300
}

variable "appautoscaling_scale_out_cooldown" {
  default = 60
}

variable "appautoscaling_target_value" {
  default     = 75
  description = "If ECSServiceAverageCPUUtilization is above this value, appautoscaling will be triggered"
}

variable "container_placement_strategy_field" {
  default = "cpu"
}

variable "container_placement_strategy_type" {
  default = "binpack"
}

# uploads_consumer
variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}

variable "deployment_minimum_healthy_percent" {
  default = 100
}

variable "deployment_maximum_percent" {
  default = 200
}

variable "uploads_consumer_container_cpu" {
  default = "1024"
}

variable "uploads_consumer_container_memory" {
  default = "1024"
}

variable "uploads_consumer_image_url" {
  default = "pennsieve/uploads-consumer"
}

# clamd

variable "clamd_container_port" {
  default = "3310"
}

variable "clamd_container_cpu" {
  default = "1024"
}

variable "clamd_container_memory_reservation" {
  default = "1280"
}

variable "clamd_container_memory" {
  default = "2048"
}

variable "clamd_image_url" {
  default = "pennsieve/clamd"
}
