# =============================================================================
# network module — 入力契約（確定済み）
# =============================================================================

variable "prefix" {
  description = "リソース名のプレフィクス（例: mannschaft）"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC の CIDR ブロック（例: 10.0.0.0/16）"
  type        = string
}
