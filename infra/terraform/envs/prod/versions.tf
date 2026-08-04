# =============================================================================
# Terraform / プロバイダのバージョン固定
# =============================================================================
# use_lockfile（S3 ネイティブロック）が 1.10 以降のため required_version >= 1.10。

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.57"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.0"
    }
    # Cloudflare Tunnel のシークレット自動生成に使用（edge module の random_id）
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}
