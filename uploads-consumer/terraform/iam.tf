# Create ECS Task IAM Role / Policy
resource "aws_iam_role" "ecs_task_iam_role" {
  name = "${var.environment_name}-${var.service_name}-ecs-task-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement":
  [
    {
      "Sid": "",
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

resource "aws_iam_policy" "ecs_task_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-ecs-task-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.ecs_task_iam_policy_document.json
}

resource "aws_iam_role_policy_attachment" "ecs_task_iam_role_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.id
  policy_arn = aws_iam_policy.ecs_task_iam_policy.arn
}

data "aws_iam_policy_document" "ecs_task_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]

    resources = ["${data.terraform_remote_state.ecs_cluster.outputs.cloudwatch_log_group_arn}:*"]
  }

  statement {
    effect = "Allow"

    actions = [
      "ec2:AuthorizeSecurityGroupIngress",
      "ec2:Describe*",
      "elasticloadbalancing:DeregisterInstancesFromLoadBalancer",
      "elasticloadbalancing:DeregisterTargets",
      "elasticloadbalancing:Describe*",
      "elasticloadbalancing:RegisterInstancesWithLoadBalancer",
      "elasticloadbalancing:RegisterTargets",
    ]

    resources = ["*"]
  }

  statement {
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.notifications_kms_key_arn,
      data.terraform_remote_state.etl_infrastructure.outputs.sqs_kms_key_arn,
    ]
  }

  statement {
    effect = "Allow"

    actions = [
      "sqs:DeleteMessage",
      "sqs:DeleteMessageBatch",
      "sqs:ReceiveMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:ChangeMessageVisibilityBatch",
    ]

    resources = [
      data.terraform_remote_state.etl_infrastructure.outputs.uploads_queue_arn,
      data.terraform_remote_state.etl_infrastructure.outputs.uploads_deadletter_queue_arn,
    ]
  }

  # statement {
  #   effect    = "Allow"
  #   actions   = ["sns:Publish"]
  #   resources = [data.terraform_remote_state.account.outputs.data_management_victor_ops_sns_topic_arn]
  # }

  statement {
    effect = "Allow"

    actions = [
      "sqs:SendMessage",
      "sqs:SendMessageBatch",
    ]

    resources = [data.terraform_remote_state.platform_infrastructure.outputs.notifications_queue_arn]
  }

  statement {
    effect = "Allow"

    actions = [
      "ssm:GetParameters",
      "secretsmanager:GetSecretValue",
      "kms:Decrypt",
    ]

    resources = [
      "arn:aws:ssm:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:parameter/${var.environment_name}/${var.service_name}/*",
      "arn:aws:secretsmanager:::secret/${var.environment_name}/${var.service_name}/*",
      "arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm",
    ]
  }

  statement {
    effect  = "Allow"
    actions = ["s3:*"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
    ]
  }
}

# Create ECS Autoscaling IAM Role / Policy
resource "aws_iam_role" "ecs_as_iam_role" {
  name = "${var.environment_name}-${var.service_name}-ecs-as-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "application-autoscaling.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

resource "aws_iam_policy" "ecs_as_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-ecs-as-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.ecs_as_iam_policy_document.json
}

resource "aws_iam_role_policy_attachment" "ecs_as_iam_role_policy_attachment" {
  role       = aws_iam_role.ecs_as_iam_role.id
  policy_arn = aws_iam_policy.ecs_as_iam_policy.arn
}

data "aws_iam_policy_document" "ecs_as_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService",
    ]

    resources = ["*"]
  }

  statement {
    effect    = "Allow"
    actions   = ["cloudwatch:DescribeAlarms"]
    resources = ["*"]
  }
}
