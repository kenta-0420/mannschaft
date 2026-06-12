# =============================================================================
# data module — 出力契約（確定済み）
# =============================================================================

output "db_endpoint" {
  description = "RDS MySQL のエンドポイント（host:3306 形式。app module の SPRING_DATASOURCE_URL 組み立てに使用。:3306 を追記しないこと）"
  # aws_db_instance.endpoint は "hostname:3306" の形式で返る
  value = aws_db_instance.main.endpoint
}

output "db_name" {
  description = "RDS MySQL の DB 名（app module が SPRING_DATASOURCE_URL に組み込む）"
  value       = aws_db_instance.main.db_name
}

output "db_username" {
  description = "RDS マスターユーザー名（app module が SPRING_DATASOURCE_USERNAME として ECS タスク定義に注入する）"
  value       = aws_db_instance.main.username
}

output "db_master_user_secret_arn" {
  description = "RDS マスターユーザーの Secrets Manager シークレット ARN（manage_master_user_password 由来。app module が ECS タスク定義の secrets で参照）"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "valkey_primary_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント（ホスト名のみ。ポート 6379 は app module で固定）"
  value       = aws_elasticache_replication_group.valkey.primary_endpoint_address
}
