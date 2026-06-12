# =============================================================================
# edge module — 出力契約（確定済み）
# =============================================================================

output "pages_project_name" {
  description = "Cloudflare Pages プロジェクト名（FE デプロイ workflow が wrangler で指定する）"
  value       = cloudflare_pages_project.frontend.name
}

output "r2_bucket_name" {
  description = "Cloudflare R2 バケット名（BE の S3 互換クライアント設定に使用）"
  value       = cloudflare_r2_bucket.storage.name
}
