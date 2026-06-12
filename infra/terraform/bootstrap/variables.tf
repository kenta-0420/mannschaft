# =============================================================================
# bootstrap 層の入力変数
# =============================================================================

variable "aws_region" {
  description = "AWS リージョン（東京）"
  type        = string
  default     = "ap-northeast-1"
}

variable "state_bucket_name" {
  description = "Terraform state 保管用 S3 バケット名。S3 バケット名は全世界で一意のため、例: mannschaft-tfstate-<AWSアカウントID> のようにアカウント ID を混ぜると衝突しない"
  type        = string
}

variable "monthly_budget_usd" {
  description = "AWS Budgets の月次予算上限（USD）。実額 80%/100%、予測 100% でメール通知"
  type        = number
  default     = 100
}

variable "alert_email" {
  description = "予算アラートの通知先メールアドレス"
  type        = string
}

variable "github_repository" {
  description = "OIDC 信頼条件に使う GitHub リポジトリ（owner/repo 形式）"
  type        = string
  default     = "kenta-0420/mannschaft"
}
