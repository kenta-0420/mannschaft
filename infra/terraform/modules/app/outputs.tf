# =============================================================================
# app module — 出力契約（確定済み）
# =============================================================================

output "ecr_repository_url" {
  description = "Spring Boot イメージの ECR リポジトリ URL（deploy workflow の push 先）"
  value       = aws_ecr_repository.backend.repository_url
}

output "cloudflared_tunnel_token_secret_arn" {
  description = "cloudflared Tunnel トークンを投入する Secrets Manager の箱の ARN（apply 後に手動で値を put する先）"
  value       = aws_secretsmanager_secret.cloudflared_tunnel_token.arn
}

output "task_role_arn" {
  description = "ECS タスクロール ARN（SES 送信等のアプリ実行時権限。bootstrap の app-deploy PassRole 絞り込みにも使用）"
  value       = aws_iam_role.task.arn
}

output "ecs_cluster_name" {
  description = "ECS クラスタ名（deploy workflow の ECS_CLUSTER）"
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "ECS サービス名（deploy workflow の ECS_SERVICE）"
  value       = aws_ecs_service.app.name
}

# F09.6 Phase 8a: SES 通知 SQS / SNS（SES Identity 結線・運用確認に使用）
output "ses_notifications_queue_name" {
  description = "SES バウンス/苦情通知の SQS キュー名（Spring Boot の SES_NOTIFICATION_QUEUE_NAME に注入済み）"
  value       = aws_sqs_queue.ses_notifications.name
}

output "ses_notifications_queue_arn" {
  description = "SES 通知 SQS キューの ARN"
  value       = aws_sqs_queue.ses_notifications.arn
}

output "ses_notifications_dlq_arn" {
  description = "SES 通知 SQS の DLQ ARN（滞留メッセージの調査用）"
  value       = aws_sqs_queue.ses_notifications_dlq.arn
}

output "ses_notifications_topic_arn" {
  description = "SES 通知 SNS Topic ARN（SES Identity / Configuration Set の通知先に結線する）"
  value       = aws_sns_topic.ses_notifications.arn
}
