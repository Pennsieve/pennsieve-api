# ECS Task Definition for Delete Task (invoked after successful dataset publish)

resource "aws_ecs_task_definition" "delete_task" {
  family                   = "${var.environment_name}-${var.service_name}-delete-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.ecs_delete_task_cpu
  memory                   = var.ecs_delete_task_memory
  execution_role_arn       = aws_iam_role.delete_task_execution_role.arn
  task_role_arn            = aws_iam_role.delete_task_role.arn

  container_definitions = jsonencode([
    {
      name      = var.ecs_delete_task_container_name
      image     = var.ecs_delete_task_image
      essential = true

      environment = [
        {
          name  = "ENVIRONMENT"
          value = var.environment_name
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.delete_task.name
          "awslogs-region"        = data.aws_region.current_region.name
          "awslogs-stream-prefix" = "delete-task"
        }
      }
    }
  ])

  tags = {
    Name        = "${var.environment_name}-${var.service_name}-delete-task"
    Environment = var.environment_name
    Service     = var.service_name
  }
}

# CloudWatch Log Group for Delete Task
resource "aws_cloudwatch_log_group" "delete_task" {
  name              = "/aws/ecs/${var.environment_name}-${var.service_name}-delete-task"
  retention_in_days = 30

  tags = {
    Name        = "${var.environment_name}-${var.service_name}-delete-task-logs"
    Environment = var.environment_name
    Service     = var.service_name
  }
}

# IAM Role for Task Execution (pulling images, writing logs)
resource "aws_iam_role" "delete_task_execution_role" {
  name = "${var.environment_name}-${var.service_name}-delete-task-exec-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "${var.environment_name}-${var.service_name}-delete-task-exec-role"
    Environment = var.environment_name
    Service     = var.service_name
  }
}

resource "aws_iam_role_policy_attachment" "delete_task_execution_role_policy" {
  role       = aws_iam_role.delete_task_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# IAM Role for Task (permissions the container needs at runtime)
resource "aws_iam_role" "delete_task_role" {
  name = "${var.environment_name}-${var.service_name}-delete-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "${var.environment_name}-${var.service_name}-delete-task-role"
    Environment = var.environment_name
    Service     = var.service_name
  }
}

# Add permissions for the delete task as needed
# Example: S3 access for cleanup operations
resource "aws_iam_role_policy" "delete_task_policy" {
  name = "${var.environment_name}-${var.service_name}-delete-task-policy"
  role = aws_iam_role.delete_task_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn,
          "${data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn}/*"
        ]
      }
    ]
  })
}
