# Create policies

resource "aws_iam_policy" "batch_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-batch-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.batch_iam_policy_document.json
}

resource "aws_iam_policy" "cognito_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-cognito-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.cognito_iam_policy_document.json
}

resource "aws_iam_policy" "kms_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-kms-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.kms_iam_policy_document.json
}

resource "aws_iam_policy" "s3_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-s3-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.s3_iam_policy_document.json
}

resource "aws_iam_policy" "ses_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-ses-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.ses_iam_policy_document.json
}

resource "aws_iam_policy" "sqs_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-sqs-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.sqs_iam_policy_document.json
}

resource "aws_iam_policy" "ssm_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-ssm-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.ssm_iam_policy_document.json
}

resource "aws_iam_policy" "sts_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-sts-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.sts_iam_policy_document.json
}

# Policy Attachments to ECS Task Role

resource "aws_iam_role_policy_attachment" "batch_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.batch_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "cognito_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.cognito_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "kms_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.kms_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "s3_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.s3_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "ses_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.ses_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "sqs_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.sqs_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "ssm_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.ssm_iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "sts_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.sts_iam_policy.arn
}

# Policy Documents

data "aws_iam_policy_document" "batch_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "batch:DescribeJobDefinitions",
      "batch:SubmitJob",
    ]

    resources = ["*"]
  }
}

data "aws_iam_policy_document" "cognito_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "cognito-idp:AdminCreateUser",
    ]

    resources = [
      data.terraform_remote_state.cognito.outputs.user_pool_arn,
    ]
  }

  statement {
    effect = "Allow"

    actions = [
      "cognito-idp:AdminCreateUser",
      "cognito-idp:AdminDeleteUser",
      "cognito-idp:AdminSetUserPassword",
    ]

    resources = [
      data.terraform_remote_state.cognito.outputs.token_pool_arn,
    ]
  }
}

data "aws_iam_policy_document" "kms_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "kms:CreateAlias",
      "kms:CreateKey",
      "kms:Describe*",
      "kms:Get*",
      "kms:List*",
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
      data.terraform_remote_state.platform_infrastructure.outputs.jobs_kms_key_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.notifications_kms_key_arn,
      data.terraform_remote_state.etl_infrastructure.outputs.sqs_kms_key_arn,
    ]
  }
}

data "aws_iam_policy_document" "s3_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "s3:*",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      # data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      # "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn}/*",
    ]
  }

  statement {
    effect = "Allow"

    actions = [
      "s3:List*",
    ]

    resources = [
      "*",
    ]
  }
}

data "aws_iam_policy_document" "ses_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "ses:SendEmail",
      "ses:SendRawEmail",
    ]

    resources = ["*"]
  }
}

data "aws_iam_policy_document" "sqs_iam_policy_document" {
  statement {
    effect  = "Allow"
    actions = ["sqs:*"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.notifications_queue_arn,
      data.terraform_remote_state.etl_infrastructure.outputs.uploads_queue_arn,
    ]
  }
}

data "aws_iam_policy_document" "ssm_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]

    resources = ["arn:aws:ssm:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:parameter/${var.environment_name}/${var.service_name}/*"]
  }

  statement {
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
  }

  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:ListSecrets"]
    resources = ["*"]
  }

  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["arn:aws:secretsmanager:::secret/${var.environment_name}/${var.service_name}/*"]
  }
}

data "aws_iam_policy_document" "sts_iam_policy_document" {
  statement {
    effect    = "Allow"
    actions   = ["sts:AssumeRole"]
    resources = [aws_iam_role.uploader_iam_role.arn]
  }

  statement {
    effect    = "Allow"
    actions   = ["sts:GetFederationToken"]
    resources = ["*"]
  }
}

# Create uploader role / policy for doling out STS tokens to clients uploading files.

resource "aws_iam_role" "uploader_iam_role" {
  name               = "${var.environment_name}-${var.service_name}-uploader-role-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  assume_role_policy = data.aws_iam_policy_document.uploader_iam_role_policy_document.json
}

resource "aws_iam_policy" "uploader_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-uploader-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.uploader_iam_policy_document.json
}

resource "aws_iam_role_policy_attachment" "uploader_iam_role_policy_attachment" {
  role       = aws_iam_role.uploader_iam_role.id
  policy_arn = aws_iam_policy.uploader_iam_policy.arn
}

data "aws_iam_policy_document" "uploader_iam_policy_document" {
  statement {
    effect = "Allow"

    actions = [
      "s3:ListBucket",
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn}/*",
    ]
  }
}

data "aws_iam_policy_document" "uploader_iam_role_policy_document" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = [var.ecs_task_iam_role_arn]
    }
  }
}
