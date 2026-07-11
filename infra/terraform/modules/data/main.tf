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

# -----------------------------------------------------------------------------
# RDS サブネットグループ（private subnets に配置）
# -----------------------------------------------------------------------------
resource "aws_db_subnet_group" "main" {
  name       = "${var.prefix}-db-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "${var.prefix}-db-subnet-group"
  }
}

# -----------------------------------------------------------------------------
# RDS パラメータグループ（MySQL 8.0 / ローカル docker-compose.yml の MySQL 設定と整合）
#
# docker-compose.yml との差異:
#   - time_zone: C12 修正 — docker では TZ=Asia/Tokyo だが RDS 本番では UTC に統一。
#     アプリ（Spring Boot）は UTC 正準・FE で表示変換の設計のため、
#     JDBC URL の serverTimezone=UTC と一致させる。
#     docker-compose.yml の MySQL コンテナも UTC に合わせることを推奨する。
#   - collation_server: docker では utf8mb4_unicode_ci（MySQL 5.x 互換）だが
#     MySQL 8.0 では utf8mb4_0900_ai_ci が推奨デフォルト（ICU ベースで照合精度向上）。
#     RDS 本番では 8.0 標準の 0900_ai_ci を採用する。
#   - long_query_time: docker では 1.0 秒だが、RDS 本番では 2 秒に設定
#     （本番トラフィックでのノイズを減らすため。必要に応じて引き下げ可）
#   - log_queries_not_using_indexes: docker では OFF（0）。RDS でも同様に 0 とする
#   - log_slow_admin_statements: docker では 1（ON）。RDS でも同様に設定
#   - slow_query_log_file はパラメータグループ外（RDS が管理するため設定不要）
# -----------------------------------------------------------------------------
resource "aws_db_parameter_group" "mysql8" {
  name   = "${var.prefix}-mysql8"
  family = "mysql8.0"

  # 文字コード: ローカル docker-compose.yml と同様に utf8mb4 に統一
  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  # 照合順序: MySQL 8.0 標準の ICU ベース照合順序（docker では unicode_ci だが本番は 0900_ai_ci）
  parameter {
    name  = "collation_server"
    value = "utf8mb4_0900_ai_ci"
  }

  # C12 修正: JDBC URL の serverTimezone=UTC と整合させるため UTC に統一
  # アプリは UTC 正準設計のため RDS 側も UTC にする（docker-compose も UTC 推奨）
  parameter {
    name  = "time_zone"
    value = "UTC"
  }

  # スロークエリログ: ローカル docker と同様に有効化
  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  # スロークエリ閾値: ローカル docker は 1.0 秒だが本番は 2 秒（ノイズ軽減）
  parameter {
    name  = "long_query_time"
    value = "2"
  }

  # インデックス未使用クエリのログ: ローカル docker と同様に OFF（スロークエリ閾値で絞る）
  parameter {
    name  = "log_queries_not_using_indexes"
    value = "0"
  }

  # 管理系ステートメント（ALTER TABLE 等）もスロークエリ対象に含める（docker と同様）
  parameter {
    name  = "log_slow_admin_statements"
    value = "1"
  }

  tags = {
    Name = "${var.prefix}-mysql8"
  }
}

# -----------------------------------------------------------------------------
# RDS インスタンス（MySQL 8.0）
# -----------------------------------------------------------------------------
resource "aws_db_instance" "main" {
  identifier     = "${var.prefix}-mysql"
  engine         = "mysql"
  engine_version = "8.0"
  instance_class = var.db_instance_class
  db_name        = "mannschaft"
  username       = "admin"

  # パスワードは Secrets Manager で自動管理（Terraform state に平文を残さない）
  manage_master_user_password = true

  # ストレージ: gp3 20GB ベース、最大 100GB まで自動拡張
  storage_type          = "gp3"
  allocated_storage     = 20
  max_allocated_storage = 100

  # パラメータグループ（文字コード / タイムゾーン / スロークエリ設定）
  parameter_group_name = aws_db_parameter_group.mysql8.name

  # サブネット・ネットワーク
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [var.db_sg_id]
  publicly_accessible    = false

  # 可用性: Multi-AZ なし（最小構成・コスト優先）
  multi_az = false

  # バックアップ: 7 日間保持
  backup_retention_period = 7

  # 削除保護: 誤削除防止のため有効化（解除は手動操作が必要）
  deletion_protection = true

  # 削除時のスナップショット: 最終スナップショットを残す
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.prefix}-mysql-final-snapshot"

  tags = {
    Name = "${var.prefix}-mysql"
  }
}

# -----------------------------------------------------------------------------
# ElastiCache サブネットグループ（private subnets に配置）
# -----------------------------------------------------------------------------
resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.prefix}-cache-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "${var.prefix}-cache-subnet-group"
  }
}

# -----------------------------------------------------------------------------
# ElastiCache Replication Group（Valkey）
#
# engine = "valkey": AWS provider 5.x で対応。Redis 互換の OSS フォーク。
# ローカル開発: docker-compose.yml で valkey/valkey:8-alpine を使用（ポート 6379）。
#
# transit_encryption_enabled = true（2026-07-11 隊3・設計:
#   docs/architecture/websocket_external_broker_valkey.md §8.6・A.7）:
#   WebSocket 外部ブローカー化（Valkey Pub/Sub relay・PR #2231 で BE 中核実装済み）により、
#   relay flag（MANNSCHAFT_WEBSOCKET_RELAY_ENABLED）ON 時にチャット本文・通知本文（PII）が
#   Valkey pub/sub チャネルを経由する新経路が生まれる。relay を ON にする前に転送時暗号化を
#   有効化しておく必要があるため、本番未 apply（このリソースはまだ AWS 上に存在しない）の
#   段階で TLS 有りの構成として初めて作成する。
#   → 設計 A.7 の懸念（既存稼働中クラスタへの in-place 変更はダウンタイム/
#     transit_encryption_mode の段階適用が必要になりうる）は、本番が未 apply のため
#     そもそも発生しない（初回作成から TLS 有効な状態でスタートする）。
#   アプリ側は application-prod.yml で spring.data.redis.ssl.enabled を
#   SPRING_REDIS_SSL_ENABLED 環境変数から注入する（terraform: modules/app/main.tf）。
#   接続は単一の LettuceConnectionFactory のため、キャッシュ・レート制限・
#   presence TTL・relay の全 Redis 利用がまとめて TLS 化される。
# -----------------------------------------------------------------------------
resource "aws_elasticache_replication_group" "valkey" {
  replication_group_id = "${var.prefix}-valkey"
  # AWS リソースの description は非 ASCII を拒否するものがある（IAM で実証）ため英語で統一
  description = "Mannschaft Valkey (Redis-compatible) cache"

  engine             = "valkey"
  node_type          = var.cache_node_type
  port               = 6379
  num_cache_clusters = 1

  # 自動フェイルオーバー: ノード 1 台構成のため無効（最小構成）
  automatic_failover_enabled = false

  # サブネット・ネットワーク
  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [var.cache_sg_id]

  # 保存時暗号化: 有効化
  at_rest_encryption_enabled = true

  # 転送時暗号化: 有効化（理由は上部コメント参照。§8.6・A.7）
  transit_encryption_enabled = true

  tags = {
    Name = "${var.prefix}-valkey"
  }
}
