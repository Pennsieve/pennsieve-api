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

# Import API Redis Data
data "terraform_remote_state" "pennsieve_redis" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/pennsieve-redis/terraform.tfstate"
    region = "us-east-1"
  }
}

data "aws_ssm_parameter" "redis_auth_token" {
  name = "/${var.environment_name}/pennsieve-redis/auth-token"
}

# Import BF Postgres Data
data "terraform_remote_state" "pennsieve_postgres" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/pennsieve-postgres/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Model Service Data
data "terraform_remote_state" "model_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/model-service/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Data Postgres Data
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

# Import DOI Service Data
data "terraform_remote_state" "doi_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/doi-service/terraform.tfstate"
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

# Import ETL Infrastructure
data "terraform_remote_state" "etl_infrastructure" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/etl-infrastructure/terraform.tfstate"
    region = "us-east-1"
  }
}

data "terraform_remote_state" "gateway" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/gateway/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Job Scheduling Service Data
data "terraform_remote_state" "job_scheduling_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/job-scheduling-service/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Notifications Data
data "terraform_remote_state" "notifications_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/notifications-service/terraform.tfstate"
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

# Import Region Data
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
