# =============================================================================
# network module — 出力契約（確定済み）
# =============================================================================

output "vpc_id" {
  description = "VPC の ID"
  value       = null # TODO: 二番隊が実装
}

output "public_subnet_ids" {
  description = "public subnet の ID 一覧（2 AZ。ALB / ECS タスク配置用）"
  value       = null # TODO: 二番隊が実装
}

output "private_subnet_ids" {
  description = "private subnet の ID 一覧（2 AZ。RDS / ElastiCache 配置用）"
  value       = null # TODO: 二番隊が実装
}

output "alb_sg_id" {
  description = "ALB 用セキュリティグループ ID"
  value       = null # TODO: 二番隊が実装
}

output "app_sg_id" {
  description = "ECS（Spring Boot）タスク用セキュリティグループ ID"
  value       = null # TODO: 二番隊が実装
}

output "db_sg_id" {
  description = "RDS 用セキュリティグループ ID"
  value       = null # TODO: 二番隊が実装
}

output "cache_sg_id" {
  description = "ElastiCache Valkey 用セキュリティグループ ID"
  value       = null # TODO: 二番隊が実装
}
