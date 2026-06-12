# =============================================================================
# edge module — 入力契約（確定済み）
# =============================================================================

variable "cloudflare_account_id" {
  description = "Cloudflare アカウント ID（Pages / R2 の作成先）"
  type        = string
}

variable "cloudflare_zone_id" {
  description = "対象ドメインの Cloudflare ゾーン ID（DNS レコードの作成先）"
  type        = string
}

variable "domain_name" {
  description = "本番ドメイン名（例: mannschaft.example.com）"
  type        = string
}

variable "alb_dns_name" {
  description = "AWS ALB の DNS 名（/api/** ・ /ws のルーティング先。app module の alb_dns_name）"
  type        = string
}

variable "acm_domain_validation_options" {
  description = "ACM 証明書の DNS 検証レコード情報（app module の acm_domain_validation_options）。Cloudflare に proxied=false の検証 CNAME を作成する"
  type = list(object({
    domain_name           = string
    resource_record_name  = string
    resource_record_type  = string
    resource_record_value = string
  }))
}

variable "pages_env" {
  description = "Cloudflare Pages（Nuxt）に設定する非秘密の環境変数 map（NUXT_PUBLIC_API_BASE 等）"
  type        = map(string)
}
