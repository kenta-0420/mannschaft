# =============================================================================
# data module — 出力契約（確定済み）
# =============================================================================

output "db_endpoint" {
  description = "RDS MySQL のエンドポイント（host:port 形式。app module の SPRING_DATASOURCE_URL 組み立てに使用）"
  value       = null # TODO: 二番隊が実装
}

output "db_master_user_secret_arn" {
  description = "RDS マスターユーザーの Secrets Manager シークレット ARN（manage_master_user_password 由来。app module が ECS タスク定義の secrets で参照）"
  value       = null # TODO: 二番隊が実装
}

output "valkey_primary_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント（host）"
  value       = null # TODO: 二番隊が実装
}
