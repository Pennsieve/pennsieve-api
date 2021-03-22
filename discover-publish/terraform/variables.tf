variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "tier" {}

variable "vpc_name" {}

variable "image_tag" {}

variable "mount_points" {
  default = ""
}

# Fargate task resources

variable "task_memory" {
  default = 2048
}

variable "task_cpu" {
  default = 1024
}

# discover_publish

variable "discover_publish_image_url" {
  default = "pennsieve/discover-publish"
}

# postgres

variable "postgres_image_url" {
  default = "pennsieve/discover-pgdump-postgres"
}

variable "postgres_port" {
  default = 5432
}

variable "postgres_db" {
  default = "postgres"
}

variable "postgres_host" {
  default = "localhost"
}

# S3
variable "s3_copy_chunk_size" {
  default = "1073741824"
}

locals {
  # Get the `model-publish` task definition family, without the revision attached.
  # If the revision is attached, `discover-publish` needs to be deployed every
  # time `model-publish` is deployed to prevent the `model-publish` definition
  # from going stale. For example, this converts:
  #
  #   arn:aws:ecs:us-east-1:300018926035:task-definition/dev-model-publish-use1:15
  #
  # to
  #
  #   arn:aws:ecs:us-east-1:300018926035:task-definition/dev-model-publish-use1
  #
  model_publish_arn_components = split(":", data.terraform_remote_state.model_publish.outputs.ecs_task_definition_arn)
  model_publish_task_definition_family = join(":", slice(local.model_publish_arn_components, 0, length(local.model_publish_arn_components) - 1))

  # Similar to the above, prefer the discover-publish task definition family to a 
  # specific revision. This prevents the old revision from going stale if a 
  # deployment occurs while publishing.
  discover_publish_arn_components = split(":", aws_ecs_task_definition.ecs_task_definition.arn)
  discover_publish_task_definition_family = join(":", slice(local.discover_publish_arn_components, 0, length(local.discover_publish_arn_components) - 1))
}
