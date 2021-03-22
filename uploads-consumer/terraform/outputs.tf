output "ecs_service_cluster" {
  value = aws_ecs_service.ecs_service.cluster
}

output "ecs_service_desired_count" {
  value = aws_ecs_service.ecs_service.desired_count
}

output "ecs_service_iam_role" {
  value = aws_ecs_service.ecs_service.iam_role
}

output "ecs_service_id" {
  value = aws_ecs_service.ecs_service.id
}

output "ecs_service_name" {
  value = aws_ecs_service.ecs_service.name
}

output "ecs_task_definition_arn" {
  value = aws_ecs_task_definition.ecs_task_definition.arn
}

output "ecs_task_definition_family" {
  value = aws_ecs_task_definition.ecs_task_definition.family
}

output "ecs_task_definition_revision" {
  value = aws_ecs_task_definition.ecs_task_definition.revision
}
