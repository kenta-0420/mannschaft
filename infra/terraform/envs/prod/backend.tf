# =============================================================================
# Terraform state backend（S3 + ネイティブロック）
# =============================================================================
# DynamoDB ロックテーブルは不要。Terraform 1.10+ の use_lockfile = true により
# S3 上の「<key>.tflock」オブジェクトでロックを表現する（コスト 0 円）。

terraform {
  backend "s3" {
    # TODO: bootstrap apply の output `state_bucket_name` の値に置換せよ
    #       （例: mannschaft-tfstate-123456789012）
    bucket       = "REPLACE_ME-mannschaft-tfstate"
    key          = "envs/prod/terraform.tfstate"
    region       = "ap-northeast-1"
    use_lockfile = true
  }
}
