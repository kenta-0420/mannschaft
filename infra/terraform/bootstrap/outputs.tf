# =============================================================================
# bootstrap 層の出力
# =============================================================================
# apply 後にここに表示される値を GitHub の variables / envs/prod/backend.tf に
# 転記する（手順は README.md 参照）。

output "state_bucket_name" {
  description = "Terraform state 用 S3 バケット名。envs/prod/backend.tf の bucket に転記する"
  value       = aws_s3_bucket.tfstate.bucket
}

output "tf_plan_role_arn" {
  description = "PR 上の terraform plan 用 IAM ロール ARN。GitHub variable INFRA_TF_PLAN_ROLE_ARN に設定する"
  value       = aws_iam_role.tf_plan.arn
}

output "tf_apply_role_arn" {
  description = "main ブランチの terraform apply 用 IAM ロール ARN。GitHub variable INFRA_TF_APPLY_ROLE_ARN に設定する"
  value       = aws_iam_role.tf_apply.arn
}

output "app_deploy_role_arn" {
  description = "アプリデプロイ用 IAM ロール ARN。将来 deploy workflow を OIDC 化する際に使用する"
  value       = aws_iam_role.app_deploy.arn
}
