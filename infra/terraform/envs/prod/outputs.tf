# =============================================================================
# envs/prod の出力
# =============================================================================
# apply 後の運用（デプロイ設定・疎通確認・Cloudflare 設定）に使う値をまとめて出す。

output "alb_dns_name" {
  description = "ALB の DNS 名。Cloudflare の /api/** ・ /ws ルーティング先"
  value       = module.app.alb_dns_name
}

output "ecr_repository_url" {
  description = "Spring Boot イメージの push 先 ECR リポジトリ URL"
  value       = module.app.ecr_repository_url
}

output "ecs_cluster_name" {
  description = "ECS クラスタ名（deploy workflow の ECS_CLUSTER に設定）"
  value       = module.app.ecs_cluster_name
}

output "ecs_service_name" {
  description = "ECS サービス名（deploy workflow の ECS_SERVICE に設定）"
  value       = module.app.ecs_service_name
}

output "db_endpoint" {
  description = "RDS MySQL のエンドポイント（private subnet 内からのみ到達可能）"
  value       = module.data.db_endpoint
}

output "valkey_primary_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント"
  value       = module.data.valkey_primary_endpoint
}

output "pages_project_name" {
  description = "Cloudflare Pages プロジェクト名（FE デプロイ先）"
  value       = module.edge.pages_project_name
}

output "r2_bucket_name" {
  description = "Cloudflare R2 バケット名（添付ファイル等のオブジェクトストレージ）"
  value       = module.edge.r2_bucket_name
}

# F09.6 Phase 8a: SES 通知 SQS / SNS（SES Identity 結線・運用確認に使用）
output "ses_notifications_topic_arn" {
  description = "SES バウンス/苦情通知の SNS Topic ARN。SES Identity / Configuration Set の通知先に結線する"
  value       = module.app.ses_notifications_topic_arn
}

output "ses_notifications_queue_name" {
  description = "SES 通知 SQS キュー名（Spring Boot の SES_NOTIFICATION_QUEUE_NAME に自動注入済み）"
  value       = module.app.ses_notifications_queue_name
}

output "ses_notifications_dlq_arn" {
  description = "SES 通知 SQS の DLQ ARN（滞留メッセージ調査用）"
  value       = module.app.ses_notifications_dlq_arn
}
