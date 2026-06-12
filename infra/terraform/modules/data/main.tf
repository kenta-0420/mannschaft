# =============================================================================
# data module — 契約スタブ（二番隊実装範囲）
# =============================================================================
# 責務: 永続データ層（RDS MySQL 8.0 + ElastiCache Valkey）。
#
# 二番隊が実装する主要リソース:
#   - aws_db_subnet_group（private_subnet_ids）
#   - aws_db_instance: MySQL 8.0 / var.db_instance_class / Multi-AZ なし（最小構成）
#       * manage_master_user_password = true で Secrets Manager 自動管理
#         （パスワードを Terraform state に平文で残さないための必須設定）
#       * 自動バックアップ有効（retention 7 日目安）/ deletion_protection = true
#   - aws_elasticache_subnet_group（private_subnet_ids）
#   - ElastiCache Valkey: var.cache_node_type / ノード 1（最小構成）
#       * engine = "valkey"（aws provider 5.x が対応。replication_group 推奨）
#
# 契約（variables.tf / outputs.tf）は確定済み。勝手に増減しないこと。
# =============================================================================

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
