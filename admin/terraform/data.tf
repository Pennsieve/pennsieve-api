# Import Current Session Data
data "aws_caller_identity" "current" {}

data "aws_region" "current_region" {}

# Import Account Data
data "terraform_remote_state" "account" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Pennsieve Postgres Data
data "terraform_remote_state" "pennsieve_postgres" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/pennsieve-postgres/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Data Postgres Data (same as Pennsieve Postgress)
data "terraform_remote_state" "data_postgres" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/pennsieve-postgres/terraform.tfstate"
    region = "us-east-1"
  }
}

 # Import Discover Service Data
 data "terraform_remote_state" "discover_service" {
   backend = "s3"

   config = {
     bucket = "${var.aws_account}-terraform-state"
     key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/discover-service/terraform.tfstate"
     region = "us-east-1"
   }
 }

# Import ECS Cluster Data
data "terraform_remote_state" "ecs_cluster" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/ecs-cluster/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Platform Infrastructure Data
data "terraform_remote_state" "platform_infrastructure" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/platform-infrastructure/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import VPC Data
data "terraform_remote_state" "region" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import VPC Data
data "terraform_remote_state" "vpc" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Cognito Authentication Data
data "terraform_remote_state" "authentication_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    # Phase 0.5f: Cognito pool outputs now served by the auth-service leaf
    # (the pools + state were moved authentication-service -> auth-service in
    # Phase 0.5; outputs re-exposed on the auth-service leaf in 0.5e / infra#897).
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/auth-service/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Upload Service V2

# Import the email-service, to send emails via its SQS queue.
data "terraform_remote_state" "email_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/email-service/terraform.tfstate"
    region = "us-east-1"
  }
}
