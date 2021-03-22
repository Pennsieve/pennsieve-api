// CREATE AUTOSCALING TARGET
resource "aws_appautoscaling_target" "appautoscaling_target" {
  max_capacity       = var.appautoscaling_target_max_capacity
  min_capacity       = var.appautoscaling_target_min_capacity
  resource_id        = "service/${data.terraform_remote_state.ecs_cluster.outputs.ecs_cluster_name}/${aws_ecs_task_definition.ecs_task_definition.family}"
  role_arn           = aws_iam_role.ecs_as_iam_role.arn
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"

  depends_on = [
    aws_ecs_service.ecs_service,
  ]
}

// CREATE AUTOSCALING POLICY
resource "aws_appautoscaling_policy" "appautoscaling_policy" {
  name               = "${aws_ecs_task_definition.ecs_task_definition.family}-scale-up"
  policy_type        = "TargetTrackingScaling"
  resource_id        = "service/${data.terraform_remote_state.ecs_cluster.outputs.ecs_cluster_name}/${aws_ecs_task_definition.ecs_task_definition.family}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"

  target_tracking_scaling_policy_configuration {
    scale_in_cooldown  = var.appautoscaling_scale_in_cooldown
    scale_out_cooldown = var.appautoscaling_scale_out_cooldown
    target_value       = var.appautoscaling_target_value

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }

  depends_on = [
    aws_appautoscaling_target.appautoscaling_target,
  ]
}
