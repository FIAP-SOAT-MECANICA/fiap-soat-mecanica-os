terraform {
  required_version = ">= 1.14.5, < 1.16.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    key          = "os/historico/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project    = var.project
      ManagedBy  = "terraform"
      Repository = "fiap-soat-mecanica-os"
    }
  }
}
