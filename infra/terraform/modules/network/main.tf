# =============================================================================
# network module — 契約スタブ（二番隊実装範囲）
# =============================================================================
# 責務: VPC・サブネット・セキュリティグループの土台一式。
#
# 二番隊が実装する主要リソース:
#   - aws_vpc（var.vpc_cidr）+ aws_internet_gateway
#   - public subnet ×2 AZ（ALB / ECS タスク用。NAT Gateway は置かずコスト最小化）
#   - private subnet ×2 AZ（RDS / ElastiCache 用。外向き経路なし）
#   - ルートテーブル（public → IGW）
#   - セキュリティグループ 4 種:
#       alb_sg   : 443/80 を Cloudflare からのみ受ける（Cloudflare IP レンジ推奨）
#       app_sg   : 8080 を alb_sg からのみ受ける
#       db_sg    : 3306 を app_sg からのみ受ける
#       cache_sg : 6379 を app_sg からのみ受ける
#
# 契約（variables.tf / outputs.tf）は確定済み。勝手に増減しないこと。
# 増減が必要なら殿に上申し envs/prod/main.tf と同時に変更する。
# =============================================================================

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
