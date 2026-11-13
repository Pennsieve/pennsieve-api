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

# Import Cognito Authentication Data
data "terraform_remote_state" "authentication_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    # Phase 0.5f: Cognito pool outputs now served by the auth-service leaf
    # (the pools + state were moved authentication-service -> auth-service in
    # Phase 0.5; outputs re-exposed on the auth-service leaf in 0.5e / infra#897).
    # NOTE: this module (Scala authorization-service) is slated for decommission
    # in Phase 0 workstream F3 (gated on F1). Re-pointed here so it stays
    # deployable/consistent until that teardown.
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/auth-service/terraform.tfstate"
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

# Import Region Data
data "terraform_remote_state" "region" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Upload Service V2
