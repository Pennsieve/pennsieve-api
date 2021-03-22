output "ecs_task_iam_role_unique_id" {
  value = aws_iam_role.ecs_task_iam_role.unique_id
}

output "state_machine_id" {
  value = aws_sfn_state_machine.sfn_state_machine.id
}
