// CREATE TASK DEFINITION TEMPLATE
data "template_file" "task_definition" {
  template = file("${path.module}/task-definition.json.tpl")

  vars = {
    aws_region                = data.aws_region.current_region.name
    cloudwatch_log_group_name = data.terraform_remote_state.ecs_cluster.outputs.cloudwatch_log_group_name
    image_tag                 = var.image_tag

    uploads_consumer_image_url        = var.uploads_consumer_image_url
    uploads_consumer_container_cpu    = var.uploads_consumer_container_cpu
    uploads_consumer_container_memory = var.uploads_consumer_container_memory

    # uploads consumer app config via ssm
    pennsieve_postgres_database_arn        = aws_ssm_parameter.pennsieve_postgres_database.arn
    pennsieve_postgres_host_arn            = aws_ssm_parameter.pennsieve_postgres_host.arn
    pennsieve_postgres_password_arn        = aws_ssm_parameter.pennsieve_postgres_password.arn
    pennsieve_postgres_port_arn            = aws_ssm_parameter.pennsieve_postgres_port.arn
    pennsieve_postgres_user_arn            = aws_ssm_parameter.pennsieve_postgres_user.arn
    etl_bucket_arn                  = aws_ssm_parameter.etl_bucket.arn
    job_scheduling_service_host_arn = aws_ssm_parameter.job_scheduling_service_host.arn
    upload_service_host_arn         = aws_ssm_parameter.upload_service_host.arn
    jwt_secret_key_arn              = aws_ssm_parameter.jwt_secret_key.arn
    notifications_sqs_queue_arn     = aws_ssm_parameter.notifications_sqs_queue.arn
    sns_alert_topic_arn             = aws_ssm_parameter.sns_alert_topic.arn
    sqs_deadletter_queue_arn        = aws_ssm_parameter.sqs_deadletter_queue.arn
    sqs_queue_arn                   = aws_ssm_parameter.sqs_queue.arn
    storage_bucket_arn              = aws_ssm_parameter.storage_bucket.arn
    uploads_bucket_arn              = aws_ssm_parameter.uploads_bucket.arn

    # uploads_consumer_container_name = var.uploads_consumer_service_name

    clamd_image_url                    = var.clamd_image_url
    clamd_container_cpu                = var.clamd_container_cpu
    clamd_container_memory             = var.clamd_container_memory
    clamd_container_memory_reservation = var.clamd_container_memory_reservation
    clamd_container_port               = var.clamd_container_port

    # clamd_container_name            = var.clamd_service_name

    environment_name               = var.environment_name
    mount_points                   = var.mount_points
    service_name                   = var.service_name
    uploads_consumer_stream_prefix = "${var.environment_name}-${var.service_name}-consumer"
    clamd_stream_prefix            = "${var.environment_name}-${var.service_name}-clamd"
  }
}

// CREATE ECS TASK DEFINITION
resource "aws_ecs_task_definition" "ecs_task_definition" {
  family                = "${var.environment_name}-${var.service_name}-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  network_mode          = "awsvpc"
  container_definitions = data.template_file.task_definition.rendered
  execution_role_arn    = aws_iam_role.ecs_task_iam_role.arn
  task_role_arn         = aws_iam_role.ecs_task_iam_role.arn

  depends_on = [
    data.template_file.task_definition,
  ]
}

// CREATE ECS SERVICE
resource "aws_ecs_service" "ecs_service" {
  name                               = aws_ecs_task_definition.ecs_task_definition.family
  cluster                            = data.terraform_remote_state.ecs_cluster.outputs.ecs_cluster_id
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent
  task_definition                    = aws_ecs_task_definition.ecs_task_definition.arn
  desired_count                      = var.appautoscaling_target_min_capacity

  network_configuration {
    subnets         = tolist(data.terraform_remote_state.vpc.outputs.private_subnet_ids)
    security_groups = [data.terraform_remote_state.platform_infrastructure.outputs.ecs_ec2_security_group_id]
  }

  // Revisit when https://github.com/terraform-providers/terraform-provider-aws/issues/11351 is addressed
  // capacity_provider_strategy {
  //   capacity_provider = data.terraform_remote_state.ecs_cluster.ecs_capacity_provider_id
  //   weight            = var.appautoscaling_target_max_capacity
  //   base              = var.appautoscaling_target_min_capacity
  // }

  ordered_placement_strategy {
    field = var.container_placement_strategy_field
    type  = var.container_placement_strategy_type
  }
  depends_on = [
    //"aws_iam_role.ecs_service_iam_role",
    aws_ecs_task_definition.ecs_task_definition,
  ]
}

# CONFORM HEALTHY DEPOYMENT
# Check every 15 seconds; fail after 10 minutes
resource "null_resource" "confirm_healthy_deployment_10_minute_timeout" {
  triggers = {
    ecs_task_definition_arn = aws_ecs_task_definition.ecs_task_definition.arn
  }

  provisioner "local-exec" {
    command = "aws --region us-east-1 ecs wait services-stable --services ${aws_ecs_service.ecs_service.name} --cluster ${data.terraform_remote_state.ecs_cluster.outputs.ecs_cluster_name} || aws --region us-east-1 ecs wait services-stable --services ${aws_ecs_service.ecs_service.name} --cluster ${data.terraform_remote_state.ecs_cluster.outputs.ecs_cluster_name}"
  }
}
