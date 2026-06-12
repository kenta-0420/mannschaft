# =============================================================================
# envs/prod の出力
# =============================================================================
# apply 後の運用（デプロイ設定・疎通確認・Cloudflare 設定）に使う値をまとめて出す。

output "alb_dns_name" {
  description = "ALB の DNS 名。Cloudflare の /api/** ・ /ws ルーティング先"
  value       = module.app.alb_dns_name
}

output "ecr_repository_url" {
  description = "Spring Boot イメージの push 先 ECR リポジトリ URL"
  value       = module.app.ecr_repository_url
}

output "ecs_cluster_name" {
  description = "ECS クラスタ名（deploy workflow の ECS_CLUSTER に設定）"
  value       = module.app.ecs_cluster_name
}

output "ecs_service_name" {
  description = "ECS サービス名（deploy workflow の ECS_SERVICE に設定）"
  value       = module.app.ecs_service_name
}

output "db_endpoint" {
  description = "RDS MySQL のエンドポイント（private subnet 内からのみ到達可能）"
  value       = module.data.db_endpoint
}

output "valkey_primary_endpoint" {
  description = "ElastiCache Valkey のプライマリエンドポイント"
  value       = module.data.valkey_primary_endpoint
}

output "pages_project_name" {
  description = "Cloudflare Pages プロジェクト名（FE デプロイ先）"
  value       = module.edge.pages_project_name
}

output "r2_bucket_name" {
  description = "Cloudflare R2 バケット名（添付ファイル等のオブジェクトストレージ）"
  value       = module.edge.r2_bucket_name
}
