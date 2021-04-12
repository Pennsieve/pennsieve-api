
 resource "aws_iam_role_policy_attachment" "s3_iam_role_policy_attachment" {
   role       = var.ecs_task_iam_role_id
   policy_arn = aws_iam_policy.s3_iam_policy.arn
 }

 data "aws_iam_policy_document" "s3_iam_policy_document" {
   statement {
     effect = "Allow"

     actions = [
       "s3:*",
     ]

     resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_bucket_arn}/*"
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