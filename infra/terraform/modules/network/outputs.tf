# =============================================================================
# network module — 出力契約（確定済み）
# =============================================================================

output "vpc_id" {
  description = "VPC の ID"
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "public subnet の ID 一覧（2 AZ。ALB / ECS タスク配置用）"
  value       = [aws_subnet.public_1a.id, aws_subnet.public_1c.id]
}

output "private_subnet_ids" {
  description = "private subnet の ID 一覧（2 AZ。RDS / ElastiCache 配置用）"
  value       = [aws_subnet.private_1a.id, aws_subnet.private_1c.id]
}

output "app_sg_id" {
  description = "ECS（Spring Boot）タスク用セキュリティグループ ID"
  value       = aws_security_group.app.id
}

output "db_sg_id" {
  description = "RDS 用セキュリティグループ ID"
  value       = aws_security_group.db.id
}

output "cache_sg_id" {
  description = "ElastiCache Valkey 用セキュリティグループ ID"
  value       = aws_security_group.cache.id
}
