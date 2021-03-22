resource "aws_iam_policy" "iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-policy-${data.terraform_remote_state.region.outputs.aws_region_shortname}"
  path   = "/service/"
  policy = data.aws_iam_policy_document.iam_policy_document.json
}

resource "aws_iam_role_policy_attachment" "iam_role_policy_attachment" {
  role       = var.ecs_task_iam_role_id
  policy_arn = aws_iam_policy.iam_policy.arn
}

data "aws_iam_policy_document" "iam_policy_document" {
  statement {
    sid       = "KMSDecryptPermissions"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
  }

  statement {
    sid       = "SecretsManagerGetPermissions"
    effect    = "Allow"
    actions   = ["secretsmanager:ListSecrets"]
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
