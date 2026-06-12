# =============================================================================
# app module — 入力契約（確定済み）
# =============================================================================

variable "prefix" {
  description = "リソース名のプレフィクス（例: mannschaft）。IAM ロール名は必ずこのプレフィクスで始めること"
  type        = string
}

variable "vpc_id" {
  description = "配置先 VPC の ID（network module の vpc_id）"
  type        = string
}

variable "public_subnet_ids" {
  description = "ALB / ECS タスクを配置する public subnet の ID 一覧（2 AZ）"
  type        = list(string)
}

variable "alb_sg_id" {
  description = "ALB に付与するセキュリティグループ ID（network module の alb_sg_id）"
  type        = string
}

variable "app_sg_id" {
  description = "ECS タスクに付与するセキュリティグループ ID（network module の app_sg_id）"
  type        = string
}

variable "domain_name" {
  description = "本番ドメイン名（ACM 証明書の対象。例: mannschaft.example.com）"
  type        = string
}

variable "db_endpoint" {
  description = "RDS MySQL のエンドポイント（data module の db_endpoint。SPRING_DATASOURCE_URL の組み立てに使用）"
  type        = string
}

variable "db_master_user_secret_arn" {
  description = "RDS マスターユーザーの Secrets Manager シークレット ARN（ECS タスク定義の secrets で参照する。平文を経由しない）"
  type        = string
}

variable "valkey_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント（data module の valkey_primary_endpoint）"
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
