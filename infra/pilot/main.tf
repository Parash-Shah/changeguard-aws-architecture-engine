terraform {
  required_version = ">= 1.12, < 2.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

# Offline mode generates a real provider plan with no credentials or AWS API calls.
# It is not evidence that a deployment succeeds in a particular AWS account.
provider "aws" {
  region                      = var.region
  access_key                  = var.offline ? "offline-plan-only" : null
  secret_key                  = var.offline ? "offline-plan-only" : null
  skip_credentials_validation = var.offline
  skip_requesting_account_id  = var.offline
  skip_metadata_api_check     = var.offline
  skip_region_validation      = var.offline
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "offline" {
  type    = bool
  default = true
}

variable "publicly_accessible" {
  type    = bool
  default = false
}

variable "multi_az" {
  type    = bool
  default = true
}

variable "db_subnet_group_name" {
  type    = string
  default = "changeguard-pilot-existing-private-subnets"
}

resource "aws_db_instance" "orders" {
  identifier                      = "changeguard-pilot-orders"
  engine                          = "postgres"
  instance_class                  = "db.t4g.medium"
  allocated_storage               = 20
  storage_type                    = "gp3"
  storage_encrypted               = true
  publicly_accessible             = var.publicly_accessible
  multi_az                        = var.multi_az
  backup_retention_period         = 7
  deletion_protection             = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  performance_insights_enabled    = true
  db_subnet_group_name            = var.db_subnet_group_name
  username                        = "changeguard_admin"
  manage_master_user_password     = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "changeguard-pilot-orders-final"
  tags = {
    Project     = "ChangeGuard"
    Environment = "sandbox"
    Owner       = "platform"
  }

}
