data "aws_caller_identity" "current" {}

data "aws_region" "current_region" {}

# Import AWS Default SecretsManager KMS Key
data "aws_kms_key" "ssm_kms_key" {
  key_id = "alias/aws/secretsmanager"
}

# IMPORT ACCOUNT DATA
data "terraform_remote_state" "account" {
  backend = "s3"

  config = {
    bucket  = "${var.aws_account}-terraform-state"
    key     = "aws/terraform.tfstate"
    region  = "us-east-1"
  }
}

# IMPORT VPC DATA
data "terraform_remote_state" "vpc" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/terraform.tfstate"
    region = "us-east-1"
  }
}

# IMPORT DISCOVER PGDUMP LAMBDA DATA
data "terraform_remote_state" "discover_pgdump_lambda" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/discover-pgdump-lambda/terraform.tfstate"
    region = "us-east-1"
  }
}

# data "terraform_remote_state" "discover_service" {
#   backend = "s3"

#   config = {
#     bucket = "${var.aws_account}-terraform-state"
#     key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/discover-service/terraform.tfstate"
#     region = "us-east-1"
#   }
# }

# IMPORT ECS CLUSTER DATA
data "terraform_remote_state" "ecs_cluster" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/ecs-cluster/terraform.tfstate"
    region = "us-east-1"
  }
}

# IMPORT FARGATE CLUSTER DATA
data "terraform_remote_state" "fargate" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/fargate/terraform.tfstate"
    region = "us-east-1"
  }
}

# IMPORT MODEL-PUBLISH DATA
data "terraform_remote_state" "model_publish" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/model-publish/terraform.tfstate"
    region = "us-east-1"
  }
}

# IMPORT PLATFORM INFRASTRUCTURE DATA
data "terraform_remote_state" "platform_infrastructure" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/platform-infrastructure/terraform.tfstate"
    region = "us-east-1"
  }
}

# IMPORT S3CLEAN LAMBDA DATA
data "terraform_remote_state" "discover_s3clean_lambda" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/discover-s3clean-lambda/terraform.tfstate"
    region = "us-east-1"
  }
}
