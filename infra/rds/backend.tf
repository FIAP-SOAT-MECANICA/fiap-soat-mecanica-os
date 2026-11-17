terraform {
  required_version = ">= 1.14.5, < 1.16.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  # bucket e region entram no init via -backend-config (o nome do bucket depende da conta AWS Academy).
  backend "s3" {
    key          = "os/rds/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}