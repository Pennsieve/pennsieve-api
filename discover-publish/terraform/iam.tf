# ECS IAM Role
resource "aws_iam_role" "ecs_task_iam_role" {
  name = "${var.environment_name}-${var.service_name}-${var.tier}-ecs-task-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

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

# Create IAM Policy
resource "aws_iam_policy" "ecs_task_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.ecs_task_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "ecs_task_iam_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.ecs_task_iam_policy.arn
}

# ECS task IAM Policy Document
data "aws_iam_policy_document" "ecs_task_iam_policy_document" {
  statement {
    sid    = "CloudwatchLogPermissions"
    effect = "Allow"

    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutDestination",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]

    resources = ["*"]
  }

  statement {
    sid    = "SSMGetParameters"
    effect = "Allow"

    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParameterHistory",
      "ssm:GetParametersByPath",
    ]

    resources = ["arn:aws:ssm:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:parameter/${var.environment_name}/${var.service_name}-${var.tier}/*"]
  }

  statement {
    sid    = "SecretsManagerPermissions"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "secretsmanager:GetSecretValue",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.docker_hub_credentials_arn,
      data.aws_kms_key.ssm_kms_key.arn,
    ]
  }

  statement {
    sid       = "KMSDecryptSSMSecrets"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
  }

  statement {
    sid     = "S3GetObject"
    effect  = "Allow"
    actions = ["s3:GetObject"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn}/*",
    ]
  }

  statement {
    sid     = "S3PutObject"
    effect  = "Allow"
    actions = ["s3:PutObject"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn}/*",
    ]
  }

  statement {
    sid     = "S3ListBucket"
    effect  = "Allow"
    actions = ["s3:ListBucket"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn,
    ]
  }

  statement {
    sid     = "S3DeleteObject"
    effect  = "Allow"
    actions = ["s3:DeleteObject"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo_bucket_arn}/*",
    ]
  }

  statement {
    sid    = "EC2Permissions"
    effect = "Allow"

    actions = [
      "ec2:DeleteNetworkInterface",
      "ec2:CreateNetworkInterface",
      "ec2:AttachNetworkInterface",
      "ec2:DescribeNetworkInterfaces",
    ]

    resources = ["*"]
  }
}

# Step Function IAM Role
resource "aws_iam_role" "sfn_state_machine_iam_role" {
  name = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "states.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

# Create IAM Policy
resource "aws_iam_policy" "sfn_state_machine_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.sfn_state_machine_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "sfn_state_machine_iam_policy_attachment" {
  role       = aws_iam_role.sfn_state_machine_iam_role.name
  policy_arn = aws_iam_policy.sfn_state_machine_iam_policy.arn
}

# Step function IAM Policy Document
data "aws_iam_policy_document" "sfn_state_machine_iam_policy_document" {
  statement {
    sid    = "RunTask"
    effect = "Allow"

    actions = [
      "ecs:RunTask",
    ]

    resources = [
      local.discover_publish_task_definition_family,
      local.model_publish_task_definition_family,
    ]
  }

  statement {
    sid    = "TaskControl"
    effect = "Allow"

    actions = [
      "ecs:StopTask",
      "ecs:DescribeTasks",
    ]

    resources = [
      "*",
    ]
  }

  statement {
    sid    = "EventsControl"
    effect = "Allow"

    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule",
    ]

    resources = [
      "arn:aws:events:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:rule/StepFunctionsGetEventsForECSTaskRule",
    ]
  }

  statement {
    sid    = "InvokeLambda"
    effect = "Allow"

    actions = [
      "lambda:InvokeFunction",
    ]

    resources = [
      data.terraform_remote_state.discover_pgdump_lambda.outputs.lambda_function_arn,
      data.terraform_remote_state.discover_s3clean_lambda.outputs.lambda_function_arn,
    ]
  }

  statement {
    sid    = "PassRole"
    effect = "Allow"

    actions = [
      "iam:PassRole",
    ]

    resources = [
      aws_iam_role.ecs_task_iam_role.arn,
      data.terraform_remote_state.model_publish.outputs.ecs_task_iam_role_arn,
    ]
  }

  statement {
    sid    = "SQSSendMessages"
    effect = "Allow"

    actions = [
      "sqs:SendMessage",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_queue_arn,
    ]
  }

  statement {
    sid    = "KMSDecryptMessages"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_kms_key_arn,
    ]
  }
}
