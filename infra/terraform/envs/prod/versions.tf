# =============================================================================
# Terraform / プロバイダのバージョン固定
# =============================================================================
# use_lockfile（S3 ネイティブロック）が 1.10 以降のため required_version >= 1.10。

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.20"
    }
  }
}
