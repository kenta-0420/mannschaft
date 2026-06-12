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

# -----------------------------------------------------------------------------
# VPC
# -----------------------------------------------------------------------------
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.prefix}-vpc"
  }
}

# -----------------------------------------------------------------------------
# インターネットゲートウェイ（public サブネット用）
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.prefix}-igw"
  }
}

# -----------------------------------------------------------------------------
# パブリックサブネット × 2 AZ（ALB / ECS タスク配置用）
# map_public_ip_on_launch = true で ECS タスクが NAT Gateway なしで
# ECR / Secrets Manager 等の AWS エンドポイントに到達できる
# （NAT Gateway は月 $40 程度かかるため、本番最小構成では不採用）
# -----------------------------------------------------------------------------
resource "aws_subnet" "public_1a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, 0)
  availability_zone       = "ap-northeast-1a"
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.prefix}-public-1a"
  }
}

resource "aws_subnet" "public_1c" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, 1)
  availability_zone       = "ap-northeast-1c"
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.prefix}-public-1c"
  }
}

# -----------------------------------------------------------------------------
# プライベートサブネット × 2 AZ（RDS / ElastiCache 配置用）
# NAT Gateway なし → 外向きインターネット経路なし（設計どおり）
# -----------------------------------------------------------------------------
resource "aws_subnet" "private_1a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 8)
  availability_zone = "ap-northeast-1a"

  tags = {
    Name = "${var.prefix}-private-1a"
  }
}

resource "aws_subnet" "private_1c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 9)
  availability_zone = "ap-northeast-1c"

  tags = {
    Name = "${var.prefix}-private-1c"
  }
}

# -----------------------------------------------------------------------------
# パブリック用ルートテーブル（→ IGW）
# プライベートサブネットはルートテーブルを明示しないことでローカルルートのみとなる
# （NAT Gateway を置かないため意図的に外向き経路を持たせない）
# -----------------------------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.prefix}-rt-public"
  }
}

resource "aws_route_table_association" "public_1a" {
  subnet_id      = aws_subnet.public_1a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_1c" {
  subnet_id      = aws_subnet.public_1c.id
  route_table_id = aws_route_table.public.id
}

# -----------------------------------------------------------------------------
# セキュリティグループ: ALB
# ingress 443 を全 IP から受け付け（0.0.0.0/0）
# TODO: 本番切替後は Cloudflare IP レンジ（https://www.cloudflare.com/ips/）に絞ること
#       edge 陣の仕上げ作業として実施予定
# -----------------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${var.prefix}-alb-sg"
  description = "ALB インバウンド: HTTPS 443 を 0.0.0.0/0 から許可"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTPS from anywhere（本番後に Cloudflare IP 範囲に限定予定）"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "全アウトバウンド許可"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.prefix}-alb-sg"
  }
}

# -----------------------------------------------------------------------------
# セキュリティグループ: ECS アプリ（Spring Boot 8080）
# ingress は alb_sg からの 8080 のみ
# -----------------------------------------------------------------------------
resource "aws_security_group" "app" {
  name        = "${var.prefix}-app-sg"
  description = "ECS タスク: 8080 を ALB SG からのみ許可"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Spring Boot HTTP from ALB SG"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "全アウトバウンド許可（ECR pull / Secrets Manager / SES 等）"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.prefix}-app-sg"
  }
}

# -----------------------------------------------------------------------------
# セキュリティグループ: RDS MySQL 3306
# ingress は app_sg からのみ
# -----------------------------------------------------------------------------
resource "aws_security_group" "db" {
  name        = "${var.prefix}-db-sg"
  description = "RDS MySQL: 3306 を ECS アプリ SG からのみ許可"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from ECS app SG"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    description = "全アウトバウンド許可"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.prefix}-db-sg"
  }
}

# -----------------------------------------------------------------------------
# セキュリティグループ: ElastiCache Valkey 6379
# ingress は app_sg からのみ
# -----------------------------------------------------------------------------
resource "aws_security_group" "cache" {
  name        = "${var.prefix}-cache-sg"
  description = "ElastiCache Valkey: 6379 を ECS アプリ SG からのみ許可"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Valkey from ECS app SG"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    description = "全アウトバウンド許可"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.prefix}-cache-sg"
  }
}
