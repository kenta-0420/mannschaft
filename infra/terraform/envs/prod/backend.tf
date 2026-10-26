# =============================================================================
# Terraform state backend（S3 + ネイティブロック）
# =============================================================================
# DynamoDB ロックテーブルは不要。Terraform 1.10+ の use_lockfile = true により
# S3 上の「<key>.tflock」オブジェクトでロックを表現する（コスト 0 円）。

terraform {
  backend "s3" {
    # bootstrap apply（2026-06-12）の output `state_bucket_name` の実値
    bucket       = "mannschaft-tfstate-180437746585"
    key          = "envs/prod/terraform.tfstate"
    region       = "ap-northeast-1"
    use_lockfile = true
  }
}
