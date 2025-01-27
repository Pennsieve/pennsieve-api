//
// IAM Policy
//
resource "aws_iam_policy" "iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.iam_policy_document.json
}

resource "aws_iam_policy" "sns_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-sns-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.sns_iam_policy_document.json
}

//
// Policy Attachments
//
resource "aws_iam_role_policy_attachment" "iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.iam_policy.arn
}

resource "aws_iam_role_policy_attachment" "sns_iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.sns_iam_policy.arn
}

//
// Policy Documents
//
data "aws_iam_policy_document" "iam_policy_document" {

  # Jobs Queue / Key Permissions
  statement {
    sid    = "KMSKeyPermissions"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.jobs_kms_key_arn,
      data.terraform_remote_state.integration_service.outputs.integration_events_kms_key_arn
    ]
  }

  statement {
    sid    = "SQSPermissions"
    effect = "Allow"

    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
    ]

    resources = [data.terraform_remote_state.platform_infrastructure.outputs.jobs_queue_arn]
  }

  # S3 Permissions
  statement {
    sid     = "S3Permissions"
    effect  = "Allow"
    actions = ["s3:*"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.upload_service_2.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.upload_service_2.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.timeseries_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.timeseries_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_bucket_arn}/*",
    ]
  }

  # SSM Secrets Permissions
  statement {
    sid       = "SSMKMSDecryptPermissions"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
  }

  statement {
    sid       = "SecretsManagerGetPermissions"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["arn:aws:secretsmanager:::secret/${var.environment_name}/${var.service_name}/*"]
  }

  statement {
    sid       = "SecretsManagerListPermissions"
    effect    = "Allow"
    actions   = ["secretsmanager:ListSecrets"]
    resources = ["*"]
  }

  statement {
    sid    = "SSMPermissions"
    effect = "Allow"

    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]

    resources = ["arn:aws:ssm:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:parameter/${var.environment_name}/${var.service_name}/*"]
  }
}

// Allow JOBS to publish messages to Events SNS Topic
data "aws_iam_policy_document" "sns_iam_policy_document" {
  statement {
    effect  = "Allow"
    actions = ["sns:*"]

    resources = [
      data.terraform_remote_state.integration_service.outputs.sns_topic_arn
    ]
  }
}
