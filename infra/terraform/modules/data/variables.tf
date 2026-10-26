# =============================================================================
# data module — 入力契約（確定済み）
# =============================================================================

variable "prefix" {
  description = "リソース名のプレフィクス（例: mannschaft）"
  type        = string
}

variable "private_subnet_ids" {
  description = "RDS / ElastiCache を配置する private subnet の ID 一覧"
  type        = list(string)
}

variable "db_sg_id" {
  description = "RDS に付与するセキュリティグループ ID（network module の db_sg_id）"
  type        = string
}

variable "cache_sg_id" {
  description = "ElastiCache に付与するセキュリティグループ ID（network module の cache_sg_id）"
  type        = string
}

variable "db_instance_class" {
  description = "RDS MySQL 8.0 のインスタンスクラス（例: db.t4g.micro）"
  type        = string
}

variable "cache_node_type" {
  description = "ElastiCache Valkey のノードタイプ（例: cache.t4g.micro）"
  type        = string
}
