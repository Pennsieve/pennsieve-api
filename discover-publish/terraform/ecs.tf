// CREATE TASK DEFINITION TEMPLATE
data "template_file" "task_definition" {
  template = file("${path.module}/task-definition.json")

  vars = {
    aws_region                = data.aws_region.current_region.name
    cloudwatch_log_group_name = data.terraform_remote_state.ecs_cluster.outputs.cloudwatch_log_group_name
    docker_hub_credentials    = data.terraform_remote_state.platform_infrastructure.outputs.docker_hub_credentials_arn
    environment_name          = var.environment_name
    image_tag                 = var.image_tag
    mount_points              = var.mount_points
    service_name              = var.service_name
    tier                      = var.tier

    discover_publish_image_url     = var.discover_publish_image_url
    discover_publish_stream_prefix = "${var.environment_name}-${var.service_name}-${var.tier}"

    postgres_image_url     = var.postgres_image_url
    postgres_stream_prefix = "${var.environment_name}-${var.service_name}-${var.tier}-postgres"
    postgres_db_arn        = aws_ssm_parameter.postgres_db.arn
    postgres_port          = aws_ssm_parameter.postgres_port.value
    postgres_port_arn      = aws_ssm_parameter.postgres_port.arn
    postgres_host_arn      = aws_ssm_parameter.postgres_host.arn
    postgres_user_arn      = aws_ssm_parameter.postgres_user.arn
    postgres_password_arn  = aws_ssm_parameter.postgres_password.arn

    s3_publish_bucket   = data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_id
    s3_pgdump_bucket    = data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_id
    s3_asset_bucket     = data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_id
    s3_asset_key_prefix = data.terraform_remote_state.platform_infrastructure.outputs.discover_bucket_dataset_assets_key_prefix
    s3_copy_chunk_size  = var.s3_copy_chunk_size
  }
}

// CREATE ECS TASK DEFINITION
resource "aws_ecs_task_definition" "ecs_task_definition" {
  family                   = "${var.environment_name}-${var.service_name}-${var.tier}-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  network_mode             = "awsvpc"
  container_definitions    = data.template_file.task_definition.rendered
  task_role_arn            = aws_iam_role.ecs_task_iam_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_iam_role.arn
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory

  depends_on = [
    data.template_file.task_definition,
    aws_iam_role.ecs_task_iam_role,
  ]
}
