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

output "cloudflared_tunnel_id" {
  description = "Cloudflare Tunnel の ID（<id>.cfargotunnel.com のルーティング先）"
  value       = cloudflare_zero_trust_tunnel_cloudflared.this.id
}

output "cloudflared_tunnel_cname" {
  description = "Cloudflare Tunnel の CNAME（<tunnel_id>.cfargotunnel.com）。origin CNAME の向き先"
  value       = cloudflare_zero_trust_tunnel_cloudflared.this.cname
}

output "cloudflared_tunnel_token" {
  description = "cloudflared サイドカーの run トークン。apply 後に AWS Secrets Manager の箱（<prefix>/cloudflared-tunnel-token）へ手動投入する"
  value       = cloudflare_zero_trust_tunnel_cloudflared.this.tunnel_token
  sensitive   = true
}
