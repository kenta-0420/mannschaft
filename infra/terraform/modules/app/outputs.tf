# =============================================================================
# app module — 出力契約（確定済み）
# =============================================================================

output "alb_dns_name" {
  description = "ALB の DNS 名（edge module が Cloudflare のルーティング先 CNAME に使用）"
  value       = null # TODO: 二番隊が実装
}

output "alb_zone_id" {
  description = "ALB の Route 53 hosted zone ID（ALIAS レコード等で必要になった場合用）"
  value       = null # TODO: 二番隊が実装
}

output "ecr_repository_url" {
  description = "Spring Boot イメージの ECR リポジトリ URL（deploy workflow の push 先）"
  value       = null # TODO: 二番隊が実装
}

output "acm_certificate_arn" {
  description = "ALB HTTPS リスナーに紐付く ACM 証明書 ARN"
  value       = null # TODO: 二番隊が実装
}

output "acm_domain_validation_options" {
  description = "ACM 証明書の DNS 検証レコード情報（edge module が Cloudflare に検証 CNAME を作成する）"
  value       = null # TODO: 二番隊が実装（aws_acm_certificate.this.domain_validation_options を渡す）
}

output "task_role_arn" {
  description = "ECS タスクロール ARN（SES 送信等のアプリ実行時権限。bootstrap の app-deploy PassRole 絞り込みにも使用）"
  value       = null # TODO: 二番隊が実装
}

output "ecs_cluster_name" {
  description = "ECS クラスタ名（deploy workflow の ECS_CLUSTER）"
  value       = null # TODO: 二番隊が実装
}

output "ecs_service_name" {
  description = "ECS サービス名（deploy workflow の ECS_SERVICE）"
  value       = null # TODO: 二番隊が実装
}
