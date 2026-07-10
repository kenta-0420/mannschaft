# =============================================================================
# app module — 入力契約（確定済み）
# =============================================================================

variable "prefix" {
  description = "リソース名のプレフィクス（例: mannschaft）。IAM ロール名は必ずこのプレフィクスで始めること"
  type        = string
}

variable "public_subnet_ids" {
  description = "ECS タスクを配置する public subnet の ID 一覧（2 AZ）"
  type        = list(string)
}

variable "app_sg_id" {
  description = "ECS タスクに付与するセキュリティグループ ID（network module の app_sg_id。Tunnel 化後はアウトバウンドのみ）"
  type        = string
}

variable "db_endpoint" {
  description = "RDS MySQL のエンドポイント（data module の db_endpoint。host:3306 形式。SPRING_DATASOURCE_URL の組み立てに使用）"
  type        = string
}

variable "db_name" {
  description = "接続先 DB 名（data module の db_name。SPRING_DATASOURCE_URL に組み込む）"
  type        = string
}

variable "db_username" {
  description = "RDS マスターユーザー名（data module の db_username。SPRING_DATASOURCE_USERNAME として注入）"
  type        = string
}

variable "db_master_user_secret_arn" {
  description = "RDS マスターユーザーの Secrets Manager シークレット ARN（ECS タスク定義の secrets で参照する。平文を経由しない）"
  type        = string
}

variable "valkey_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント（data module の valkey_primary_endpoint。ホスト名のみ。ポートは 6379 固定）"
  type        = string
}

variable "cloudflared_image" {
  description = "cloudflared サイドカーのコンテナイメージ（linux/arm64 を含むマルチアーキイメージ）。:latest の暗黙利用を防ぐため default なし。呼び出し側で固定タグを明示すること（envs/prod/variables.tf の cloudflared_image 参照）"
  type        = string
}

variable "app_env" {
  description = "Spring Boot コンテナに渡す非秘密の環境変数 map（APP_BASE_URL 等。秘密は入れない）"
  type        = map(string)
}

variable "task_cpu" {
  description = "Fargate タスクの CPU ユニット（例: 512）"
  type        = number
}

variable "task_memory" {
  description = "Fargate タスクのメモリ MiB（例: 1024）"
  type        = number
}
